import { makeFeatures, type Observation, type ModelContext } from "./inference";

export const WEIGHT_COUNT = 62;
const BASE_TEMP_C = 20;
const TEMP_SCALE_C = 10;
const MAX_EXAMPLES_PER_LOCATION = 4000;
const EPOCHS = 250;
const LEARNING_RATE = 0.012;
const L2 = 0.012;
const PRIOR_STRENGTH = 0.10;

export interface LearnedModel {
  weights: number[];
  trainingExamples: number;
  validationMaeC: number;
  validationBiasC: number;
  validationExamples: number;
  trainedAtSec: number;
  trainingStartSec: number;
  trainingEndSec: number;
  trainedLocationCount: number;
  trainingPriorWeights: number[] | null;
}
export interface TrainingExample {
  features: number[];
  targetNormalized: number;
  actualC: number;
}
export interface TrainingSeries {
  readings: Observation[];
  timezone: string;
  context: ModelContext;
}
export interface SeriesExamples {
  all: TrainingExample[];
  training: TrainingExample[];
  validation: TrainingExample[];
  startSec: number;
  endSec: number;
}

export function buildExamples(readings: Observation[], timezone: string, context: ModelContext): TrainingExample[] {
  const ordered = [...readings].sort((a, b) => a.timestampSec - b.timestampSec);
  const examples: TrainingExample[] = [];
  for (let index = 3; index < ordered.length; index++) {
    const window = ordered.slice(index - 3, index + 1);
    if (window.some((row, i) => i > 0 && row.timestampSec - window[i - 1].timestampSec !== 3600)) continue;
    const target = window[3];
    const previousLatestFirst = [window[2], window[1], window[0]];
    examples.push({
      features: makeFeatures(target.timestampSec, previousLatestFirst, timezone, context),
      targetNormalized: (target.temperatureC - BASE_TEMP_C) / TEMP_SCALE_C,
      actualC: target.temperatureC
    });
  }
  return limitExamples(examples);
}

/** Train and evaluate one location model with the same chronological 80/20 split as Android. */
export function trainLocal(
  readings: Observation[],
  timezone: string,
  context: ModelContext,
  priorModel: LearnedModel | null = null,
  trainedAtSec = Math.floor(Date.now() / 1000)
): LearnedModel | null {
  const examples = buildExamples(readings, timezone, context);
  return trainLocalFromExamples(
    examples,
    readings.length ? Math.min(...readings.map(x => x.timestampSec)) : 0,
    readings.length ? Math.max(...readings.map(x => x.timestampSec)) : 0,
    priorModel,
    trainedAtSec
  );
}

export function trainLocalFromExamples(
  examplesInput: TrainingExample[],
  trainingStartSec: number,
  trainingEndSec: number,
  priorModel: LearnedModel | null = null,
  trainedAtSec = Math.floor(Date.now() / 1000)
): LearnedModel | null {
  const examples = limitExamples(examplesInput);
  if (examples.length < 8) return null;
  const split = Math.min(Math.max(Math.floor(examples.length * 0.8), 1), examples.length - 1);
  const training = examples.slice(0, split);
  const validation = examples.slice(split);
  const prior = priorModel?.weights?.length === WEIGHT_COUNT ? [...priorModel.weights] : null;
  const validationWeights = fitWeights(training, prior);
  const stats = evaluate(validation, validationWeights);
  const finalWeights = fitWeights(examples, prior);
  return {
    weights: finalWeights,
    trainingExamples: examples.length,
    validationMaeC: stats.maeC,
    validationBiasC: stats.biasC,
    validationExamples: stats.count,
    trainedAtSec,
    trainingStartSec,
    trainingEndSec,
    trainedLocationCount: 1,
    trainingPriorWeights: prior
  };
}

/** Train the geographic shared model from per-location examples, preserving each location's 80/20 split. */
export function trainShared(
  series: Map<string, TrainingSeries>,
  trainedAtSec = Math.floor(Date.now() / 1000)
): LearnedModel | null {
  const prepared: SeriesExamples[] = [];
  for (const [, value] of series) {
    const examples = buildExamples(value.readings, value.timezone, value.context);
    if (examples.length < 8) continue;
    const split = Math.min(Math.max(Math.floor(examples.length * 0.8), 1), examples.length - 1);
    const timestamps = value.readings.map(x => x.timestampSec);
    prepared.push({
      all: examples,
      training: examples.slice(0, split),
      validation: examples.slice(split),
      startSec: timestamps.length ? Math.min(...timestamps) : 0,
      endSec: timestamps.length ? Math.max(...timestamps) : 0
    });
  }
  return trainSharedFromSeriesExamples(prepared, trainedAtSec);
}

export function trainSharedFromSeriesExamples(
  prepared: SeriesExamples[],
  trainedAtSec = Math.floor(Date.now() / 1000)
): LearnedModel | null {
  const all = prepared.flatMap(x => x.all);
  const training = prepared.flatMap(x => x.training);
  const validation = prepared.flatMap(x => x.validation);
  if (all.length < 8 || training.length < 5 || validation.length === 0) return null;
  const validationWeights = fitWeights(training, null);
  const stats = evaluate(validation, validationWeights);
  const finalWeights = fitWeights(all, null);
  return {
    weights: finalWeights,
    trainingExamples: all.length,
    validationMaeC: stats.maeC,
    validationBiasC: stats.biasC,
    validationExamples: stats.count,
    trainedAtSec,
    trainingStartSec: Math.min(...prepared.map(x => x.startSec)),
    trainingEndSec: Math.max(...prepared.map(x => x.endSec)),
    trainedLocationCount: prepared.length,
    trainingPriorWeights: null
  };
}

function limitExamples(examples: TrainingExample[]): TrainingExample[] {
  if (examples.length <= MAX_EXAMPLES_PER_LOCATION) return examples;
  const step = examples.length / MAX_EXAMPLES_PER_LOCATION;
  return Array.from({ length: MAX_EXAMPLES_PER_LOCATION }, (_, index) => examples[Math.floor(index * step)]);
}

function fitWeights(examples: TrainingExample[], priorWeights: number[] | null): number[] {
  const weights = Array<number>(WEIGHT_COUNT).fill(0);
  if (priorWeights && priorWeights.length === WEIGHT_COUNT) {
    for (let i = 0; i < WEIGHT_COUNT; i++) weights[i] = priorWeights[i];
  } else if (examples.length) {
    weights[0] = examples.reduce((sum, x) => sum + x.targetNormalized, 0) / examples.length;
  }
  if (!examples.length) return weights;
  const divisor = examples.length;
  for (let epoch = 0; epoch < EPOCHS; epoch++) {
    const gradient = Array<number>(WEIGHT_COUNT).fill(0);
    for (const example of examples) {
      const error = dot(weights, example.features) - example.targetNormalized;
      for (let i = 0; i < WEIGHT_COUNT; i++) {
        gradient[i] += (2 * error * example.features[i]) / divisor;
      }
    }
    for (let i = 0; i < WEIGHT_COUNT; i++) {
      if (i !== 0) gradient[i] += 2 * L2 * weights[i];
      if (priorWeights && priorWeights.length === WEIGHT_COUNT) {
        gradient[i] += 2 * PRIOR_STRENGTH * (weights[i] - priorWeights[i]);
      }
      weights[i] -= LEARNING_RATE * gradient[i];
    }
  }
  return weights;
}

function evaluate(examples: TrainingExample[], weights: number[]) {
  if (!examples.length) return { maeC: Number.NaN, biasC: Number.NaN, count: 0 };
  const errors = examples.map(x => dot(weights, x.features) * TEMP_SCALE_C + BASE_TEMP_C - x.actualC);
  return {
    maeC: errors.reduce((sum, x) => sum + Math.abs(x), 0) / errors.length,
    biasC: errors.reduce((sum, x) => sum + x, 0) / errors.length,
    count: errors.length
  };
}

function dot(a: number[], b: number[]): number {
  if (a.length !== WEIGHT_COUNT || b.length !== WEIGHT_COUNT) {
    throw new Error(`Expected ${WEIGHT_COUNT} model weights and features`);
  }
  let sum = 0;
  for (let i = 0; i < WEIGHT_COUNT; i++) sum += a[i] * b[i];
  return sum;
}
