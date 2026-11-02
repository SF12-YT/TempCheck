export interface Observation {
  timestampSec: number; temperatureC: number;
  dewPointC?: number | null; windSpeedKmh?: number | null; windGustKmh?: number | null;
  pressureHpa?: number | null; humidityPct?: number | null; cloudCoverPct?: number | null;
  precipitationMm?: number | null; solarRadiationWm2?: number | null;
  cloudLowPct?: number | null; cloudMidPct?: number | null; cloudHighPct?: number | null;
  windDirectionDeg?: number | null; soilTemperatureC?: number | null; soilMoisture01cm?: number | null;
  vaporPressureDeficitKpa?: number | null; surfacePressureHpa?: number | null;
  rainMm?: number | null; showersMm?: number | null; snowfallCm?: number | null;
  weatherCode?: number | null; visibilityM?: number | null; evapotranspirationMm?: number | null;
  et0Mm?: number | null; uvIndex?: number | null; uvIndexClearSky?: number | null;
  wetBulbTemperatureC?: number | null; apparentTemperatureC?: number | null;
  solarRadiationInstantWm2?: number | null; snowDepthM?: number | null;
  directRadiationWm2?: number | null; diffuseRadiationWm2?: number | null;
  boundaryLayerHeightM?: number | null; windSpeed100mKmh?: number | null;
  windDirection100mDeg?: number | null; soilMoisture0To7cm?: number | null;
}
export interface ErrorSample {
  targetTimestampSec: number; predictedTempC: number; actualTempC: number;
  localHour: number; temperatureTrendC: number; cloudCoverPct?: number | null;
  solarRadiationWm2?: number | null; weatherCode?: number | null;
}
export interface ModelContext { latitude?: number | null; longitude?: number | null; elevationM?: number | null; }
export interface PredictionResult {
  targetTimestampSec: number; predictionMadeAtSec: number; predictedTempC: number;
  baseTempC: number; correctionC: number; verifiedPredictions: number;
  localHour: number; temperatureTrendC: number; cloudCoverPct: number | null;
  solarRadiationWm2: number | null; weatherCode: number | null; modelVersion: string;
}
const finite = (v: number | null | undefined): v is number => v != null && Number.isFinite(v);
const norm = (v: number | null | undefined, center: number, scale: number): number =>
  finite(v) ? clamp((v - center) / scale, -4, 4) : 0;
const clamp = (v: number, lo: number, hi: number): number => Math.max(lo, Math.min(hi, v));
const radians = (v: number): number => v * Math.PI / 180;
function localParts(epochSec: number, timezone: string) {
  const parts = new Intl.DateTimeFormat("en-US", {
    timeZone: timezone, year: "numeric", month: "numeric", day: "numeric", hour: "numeric", hourCycle: "h23"
  }).formatToParts(new Date(epochSec * 1000));
  const p: Record<string, number> = {};
  for (const x of parts) if (x.type !== "literal") p[x.type] = Number(x.value);
  const date = new Date(Date.UTC(p.year, p.month - 1, p.day));
  const dayOfYear = Math.floor((date.getTime() - Date.UTC(p.year, 0, 1)) / 86400000) + 1;
  const daysInYear = new Date(Date.UTC(p.year + 1, 0, 1)).getUTCFullYear() === p.year + 1
    ? (new Date(Date.UTC(p.year + 1, 0, 1)).getTime() - Date.UTC(p.year, 0, 1)) / 86400000 : 365;
  return { hour: p.hour, dayOfYear, daysInYear };
}
function makeFeatures(targetSec: number, previous: Observation[], timezone: string, ctx: ModelContext): number[] {
  const local = localParts(targetSec, timezone);
  const hourAngle = 2 * Math.PI * local.hour / 24;
  const yearAngle = 2 * Math.PI * (local.dayOfYear - 1) / local.daysInYear;
  const latest = previous[0];
  const daylight = Math.max(0, -Math.cos(hourAngle));
  const solar = norm(latest.solarRadiationWm2, 150, 300);
  const cloud = norm(latest.cloudCoverPct, 50, 50);
  const lat = clamp((ctx.latitude ?? 0) / 90, -1, 1);
  const weather = latest.weatherCode;
  const f = [
    1, Math.sin(hourAngle), Math.cos(hourAngle), Math.sin(yearAngle), Math.cos(yearAngle),
    (previous[0].temperatureC - 20) / 10, (previous[1].temperatureC - 20) / 10, (previous[2].temperatureC - 20) / 10,
    norm(latest.dewPointC,20,10), norm(latest.windSpeedKmh,15,15), norm(latest.windGustKmh,25,25),
    norm(latest.pressureHpa,1013,20), norm(latest.humidityPct,50,50), cloud,
    norm(latest.precipitationMm,0.5,2), solar,
    clamp((previous[0].temperatureC-previous[1].temperatureC)/5,-4,4),
    clamp((previous[1].temperatureC-previous[2].temperatureC)/5,-4,4),
    solar*daylight, cloud*daylight,
    norm(latest.cloudLowPct,50,50), norm(latest.cloudMidPct,50,50), norm(latest.cloudHighPct,50,50),
    finite(latest.windDirectionDeg)?Math.sin(radians(latest.windDirectionDeg)):0,
    finite(latest.windDirectionDeg)?Math.cos(radians(latest.windDirectionDeg)):0,
    norm(latest.vaporPressureDeficitKpa,1,1), norm(latest.surfacePressureHpa,1000,20),
    norm(latest.rainMm,0.2,1), norm(latest.showersMm,0.1,0.5), norm(latest.snowfallCm,0,1),
    norm(latest.visibilityM,10000,10000), norm(latest.evapotranspirationMm,0.1,0.5),
    norm(latest.et0Mm,0.1,0.5), norm(latest.uvIndex,3,3), norm(latest.uvIndexClearSky,3,3),
    norm(latest.wetBulbTemperatureC,20,10), norm(latest.apparentTemperatureC,20,10),
    norm(latest.solarRadiationInstantWm2,150,300), norm(latest.snowDepthM,0.01,0.1),
    norm(latest.directRadiationWm2,100,200), norm(latest.diffuseRadiationWm2,50,100),
    norm(latest.boundaryLayerHeightM,1000,1500), norm(latest.windSpeed100mKmh,20,20),
    finite(latest.windDirection100mDeg)?Math.sin(radians(latest.windDirection100mDeg)):0,
    finite(latest.windDirection100mDeg)?Math.cos(radians(latest.windDirection100mDeg)):0,
    norm(latest.soilTemperatureC,20,10), norm(latest.soilMoisture0To7cm,0.25,0.25),
    weather===0?1:0, weather!=null&&weather>=1&&weather<=3?1:0,
    weather===45||weather===48?1:0, weather!=null&&weather>=51&&weather<=57?1:0,
    weather!=null&&weather>=61&&weather<=67?1:0,
    weather!=null&&((weather>=71&&weather<=77)||weather===85||weather===86)?1:0,
    weather!=null&&weather>=80&&weather<=82?1:0,
    weather!=null&&weather>=95&&weather<=99?1:0,
    lat, ctx.longitude==null?0:Math.sin(radians(ctx.longitude)), ctx.longitude==null?0:Math.cos(radians(ctx.longitude)),
    norm(ctx.elevationM,500,1000), ctx.elevationM==null?0:1, lat*Math.sin(yearAngle), lat*Math.cos(yearAngle)
  ];
  if (f.length !== 62) throw new Error(`Feature schema mismatch: expected 62, got ${f.length}`);
  return f;
}
function dot(weights: number[], features: number[]): number {
  if (weights.length !== 62 || features.length !== 62) throw new Error("Expected exactly 62 model coefficients and features");
  let total=0; for(let i=0;i<62;i++) total+=weights[i]*features[i]; return total;
}
function trendBucket(v:number):number { return v>0.35?1:v< -0.35?-1:0; }
function cloudBucket(v:number|null|undefined):number|null { return finite(v)?(v<35?0:v<70?1:2):null; }
function solarBucket(v:number|null|undefined):number|null { return finite(v)?(v<10?0:v<150?1:2):null; }
function weatherBucket(v:number|null|undefined):number|null { return v==null?null:(v===45||v===48?2:v>=51&&v<=99?1:0); }
function weightedMean(samples:ErrorSample[]):number|null {
  if(!samples.length)return null;
  let sum=0,weights=0;
  samples.forEach((s,i)=>{const w=Math.exp(-i/70);sum+=clamp(s.actualTempC-s.predictedTempC,-8,8)*w;weights+=w;});
  return weights>0?sum/weights:null;
}
function groupAdjustment(group:ErrorSample[], baseline:number,strength:number):number {
  if(group.length<4)return 0;
  const mean=weightedMean(group); if(mean==null)return 0;
  return clamp((mean-baseline)*(group.length/(group.length+12))*strength,-1,1);
}
export function calculateCorrection(samples:ErrorSample[],targetSec:number,timezone:string,trend:number,cloud:number|null,solar:number|null,weather:number|null) {
  const recent=samples.filter(s=>Number.isFinite(s.predictedTempC)&&Number.isFinite(s.actualTempC)&&Number.isFinite(s.temperatureTrendC)).sort((a,b)=>b.targetTimestampSec-a.targetTimestampSec);
  if(!recent.length)return {correctionC:0,verifiedPredictions:0,globalBiasC:0,contextAdjustmentC:0};
  const raw=weightedMean(recent)??0, confidence=recent.length/(recent.length+8);
  const targetHour=localParts(targetSec,timezone).hour;
  const hour=groupAdjustment(recent.filter(s=>s.localHour===targetHour),raw,0.40);
  const trendAdj=groupAdjustment(recent.filter(s=>trendBucket(s.temperatureTrendC)===trendBucket(trend)),raw,0.35);
  const cb=cloudBucket(cloud), cloudAdj=cb==null?0:groupAdjustment(recent.filter(s=>cloudBucket(s.cloudCoverPct)===cb),raw,0.25);
  const sb=solarBucket(solar), solarAdj=sb==null?0:groupAdjustment(recent.filter(s=>solarBucket(s.solarRadiationWm2)===sb),raw,0.25);
  const wb=weatherBucket(weather), weatherAdj=wb==null?0:groupAdjustment(recent.filter(s=>weatherBucket(s.weatherCode)===wb),raw,0.15);
  const global=clamp(raw*confidence,-2.5,2.5), context=clamp(hour+trendAdj+cloudAdj+solarAdj+weatherAdj,-1.25,1.25);
  return {correctionC:clamp(global+context,-3.5,3.5),verifiedPredictions:recent.length,globalBiasC:global,contextAdjustmentC:context};
}
export function predict(input:{
  weights:number[]; observations:Observation[]; feedback:ErrorSample[]; targetTimestampSec:number;
  timezone:string; context:ModelContext; predictionMadeAtSec:number; modelVersion?:string;
}):PredictionResult {
  const recent=input.observations.slice(-3);
  if(recent.length!==3||recent[1].timestampSec-recent[0].timestampSec!==3600||recent[2].timestampSec-recent[1].timestampSec!==3600)
    throw new Error("Three consecutive hourly observations are required");
  const lead=input.targetTimestampSec-recent[2].timestampSec;
  if(lead<=0||lead>10800)throw new Error("Target must be 1–3 hours after the latest observation");
  const base=clamp(dot(input.weights,makeFeatures(input.targetTimestampSec,[...recent].reverse(),input.timezone,input.context))*10+20,-90,70);
  const latest=recent[2],trend=latest.temperatureC-recent[1].temperatureC;
  const correction=calculateCorrection(input.feedback,input.targetTimestampSec,input.timezone,trend,latest.cloudCoverPct??null,latest.solarRadiationWm2??null,latest.weatherCode??null);
  return {targetTimestampSec:input.targetTimestampSec,predictionMadeAtSec:input.predictionMadeAtSec,
    predictedTempC:clamp(base+correction.correctionC,-90,70),baseTempC:base,correctionC:correction.correctionC,
    verifiedPredictions:correction.verifiedPredictions,localHour:localParts(input.targetTimestampSec,input.timezone).hour,
    temperatureTrendC:trend,cloudCoverPct:latest.cloudCoverPct??null,solarRadiationWm2:latest.solarRadiationWm2??null,
    weatherCode:latest.weatherCode??null,modelVersion:input.modelVersion??"templearn-62-v1"};
}
