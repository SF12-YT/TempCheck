import { predict, type ErrorSample, type ModelContext, type Observation } from "./inference";
interface Env { DB:D1Database; LOCATION_ID:string; TIMEZONE:string; LATITUDE:string; LONGITUDE:string; SYNC_KEY?:string; }
function json(data:unknown,status=200){return new Response(JSON.stringify(data),{status,headers:{"content-type":"application/json","access-control-allow-origin":"*","cache-control":"no-store"}});}
async function ensureSchema(env:Env){
 await env.DB.prepare("CREATE TABLE IF NOT EXISTS cloud_model_state (location_id TEXT PRIMARY KEY NOT NULL, weights_json TEXT NOT NULL, observations_json TEXT NOT NULL, feedback_json TEXT NOT NULL, context_json TEXT NOT NULL, timezone TEXT NOT NULL, model_version TEXT NOT NULL, updated_at TEXT NOT NULL)").run();
 await env.DB.prepare("CREATE TABLE IF NOT EXISTS cloud_observations (location_id TEXT NOT NULL, timestamp_sec INTEGER NOT NULL, temperature_c REAL NOT NULL, payload_json TEXT NOT NULL, PRIMARY KEY(location_id,timestamp_sec))").run();
 await env.DB.prepare("CREATE TABLE IF NOT EXISTS cloud_verified_forecasts (forecast_id INTEGER PRIMARY KEY, location_id TEXT NOT NULL, target_timestamp_sec INTEGER NOT NULL, predicted_temp_c REAL NOT NULL, actual_temp_c REAL NOT NULL, local_hour INTEGER NOT NULL, temperature_trend_c REAL NOT NULL, cloud_cover_pct REAL, solar_radiation_wm2 REAL, weather_code INTEGER, error_c REAL NOT NULL, verified_at TEXT NOT NULL)").run();
}
function authorized(request:Request,env:Env){return !!env.SYNC_KEY&&request.headers.get("x-templearn-key")===env.SYNC_KEY;}
function hourValue(hourly:Record<string,unknown>,key:string,index:number):number|null{
 const values=hourly[key]; if(!Array.isArray(values))return null;
 const value=values[index]; return typeof value==="number"&&Number.isFinite(value)?value:null;
}
async function fetchPastObservations(env:Env,issuedSec:number):Promise<Observation[]>{
 const fields=["temperature_2m","relative_humidity_2m","dew_point_2m","precipitation","rain","snowfall","weather_code","cloud_cover","cloud_cover_low","cloud_cover_mid","cloud_cover_high","pressure_msl","wind_speed_10m","wind_gusts_10m","shortwave_radiation"];
 const params=new URLSearchParams({latitude:env.LATITUDE,longitude:env.LONGITUDE,hourly:fields.join(","),past_hours:"24",forecast_hours:"0",timezone:"UTC",models:"best_match",timeformat:"unixtime",temperature_unit:"celsius",wind_speed_unit:"kmh",precipitation_unit:"mm"});
 const response=await fetch("https://api.open-meteo.com/v1/forecast?"+params.toString(),{headers:{"accept":"application/json","user-agent":"TempLearn-Cloud/1.0"}});
 if(!response.ok)throw new Error("Open-Meteo past-hour input request failed: HTTP "+response.status);
 const payload=await response.json() as {hourly?:Record<string,unknown>};
 const hourly=payload.hourly;if(!hourly||!Array.isArray(hourly.time))throw new Error("Open-Meteo response has no hourly history");
 const times=hourly.time as unknown[];
 const latestCompletedHour=Math.floor(issuedSec/3600)*3600;
 const rows:Observation[]=[];
 for(let i=0;i<times.length;i++){
  const timestampSec=Number(times[i]),temperatureC=hourValue(hourly,"temperature_2m",i);
  if(!Number.isFinite(timestampSec)||timestampSec>=latestCompletedHour||temperatureC==null)continue;
  rows.push({timestampSec,temperatureC,
   humidityPct:hourValue(hourly,"relative_humidity_2m",i),dewPointC:hourValue(hourly,"dew_point_2m",i),
   precipitationMm:hourValue(hourly,"precipitation",i),rainMm:hourValue(hourly,"rain",i),
   snowfallCm:hourValue(hourly,"snowfall",i),weatherCode:hourValue(hourly,"weather_code",i),
   cloudCoverPct:hourValue(hourly,"cloud_cover",i),cloudLowPct:hourValue(hourly,"cloud_cover_low",i),
   cloudMidPct:hourValue(hourly,"cloud_cover_mid",i),cloudHighPct:hourValue(hourly,"cloud_cover_high",i),
   pressureHpa:hourValue(hourly,"pressure_msl",i),windSpeedKmh:hourValue(hourly,"wind_speed_10m",i),
   windGustKmh:hourValue(hourly,"wind_gusts_10m",i),solarRadiationWm2:hourValue(hourly,"shortwave_radiation",i)});
 }
 rows.sort((a,b)=>a.timestampSec-b.timestampSec);
 if(rows.length<3)throw new Error("Fewer than three completed hourly input readings were returned");
 const recent=rows.slice(-3);
 if(recent[1].timestampSec-recent[0].timestampSec!==3600||recent[2].timestampSec-recent[1].timestampSec!==3600)
  throw new Error("Latest available past-hour inputs are not three consecutive hours");
 for(const row of rows){
  await env.DB.prepare("INSERT OR REPLACE INTO cloud_observations (location_id,timestamp_sec,temperature_c,payload_json) VALUES (?,?,?,?)")
   .bind(env.LOCATION_ID,row.timestampSec,row.temperatureC,JSON.stringify(row)).run();
 }
 return recent;
}
async function loadCloudFeedback(env:Env,uploaded:ErrorSample[]):Promise<ErrorSample[]>{
 const result=await env.DB.prepare("SELECT target_timestamp_sec,predicted_temp_c,actual_temp_c,local_hour,temperature_trend_c,cloud_cover_pct,solar_radiation_wm2,weather_code FROM cloud_verified_forecasts WHERE location_id=? ORDER BY target_timestamp_sec DESC LIMIT 5000").bind(env.LOCATION_ID).all<Record<string,unknown>>();
 const cloud=(result.results||[]).map(r=>({targetTimestampSec:Number(r.target_timestamp_sec),predictedTempC:Number(r.predicted_temp_c),actualTempC:Number(r.actual_temp_c),localHour:Number(r.local_hour),temperatureTrendC:Number(r.temperature_trend_c),cloudCoverPct:r.cloud_cover_pct==null?null:Number(r.cloud_cover_pct),solarRadiationWm2:r.solar_radiation_wm2==null?null:Number(r.solar_radiation_wm2),weatherCode:r.weather_code==null?null:Number(r.weather_code)}));
 return [...uploaded,...cloud].sort((a,b)=>b.targetTimestampSec-a.targetTimestampSec);
}
async function reconcileForecasts(env:Env,observations:Observation[],verifiedAtSec:number){
 for(const actual of observations){
  const rows=await env.DB.prepare("SELECT id,target_time,payload_json FROM forecast_vintages WHERE location_id=? AND source='templearn-algorithm' AND target_time=?").bind(env.LOCATION_ID,new Date(actual.timestampSec*1000).toISOString()).all<Record<string,unknown>>();
  for(const row of rows.results||[]){
   const prediction=JSON.parse(String(row.payload_json)) as {predictedTempC:number;localHour:number;temperatureTrendC:number;cloudCoverPct:number|null;solarRadiationWm2:number|null;weatherCode:number|null};
   if(!Number.isFinite(prediction.predictedTempC))continue;
   const error=actual.temperatureC-prediction.predictedTempC;
   await env.DB.prepare("INSERT OR IGNORE INTO cloud_verified_forecasts (forecast_id,location_id,target_timestamp_sec,predicted_temp_c,actual_temp_c,local_hour,temperature_trend_c,cloud_cover_pct,solar_radiation_wm2,weather_code,error_c,verified_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")
    .bind(Number(row.id),env.LOCATION_ID,actual.timestampSec,prediction.predictedTempC,actual.temperatureC,prediction.localHour,prediction.temperatureTrendC,prediction.cloudCoverPct,prediction.solarRadiationWm2,prediction.weatherCode,error,new Date(verifiedAtSec*1000).toISOString()).run();
  }
 }
}
async function generate(env:Env,issuedMs:number){
 const state=await env.DB.prepare("SELECT weights_json,feedback_json,context_json,timezone,model_version FROM cloud_model_state WHERE location_id=?").bind(env.LOCATION_ID).first<Record<string,unknown>>();
 if(!state)return {generated:false,reason:"No Android model state has been uploaded yet"};
 const issuedSec=Math.floor(issuedMs/1000);
 const observations=await fetchPastObservations(env,issuedSec);
 await reconcileForecasts(env,observations,issuedSec);
 const feedback=await loadCloudFeedback(env,JSON.parse(String(state.feedback_json)) as ErrorSample[]);
 const targetSec=(Math.floor(issuedMs/3600000)+1)*3600;
 const result=predict({weights:JSON.parse(String(state.weights_json)),observations,feedback,context:JSON.parse(String(state.context_json)),timezone:String(state.timezone||env.TIMEZONE),targetTimestampSec:targetSec,predictionMadeAtSec:issuedSec,modelVersion:String(state.model_version)});
 const issued=new Date(issuedMs).toISOString(),target=new Date(targetSec*1000).toISOString();
 await env.DB.prepare("INSERT OR IGNORE INTO forecast_vintages (location_id,issued_at,target_time,source,payload_json) VALUES (?,?,?,'templearn-algorithm',?)").bind(env.LOCATION_ID,issued,target,JSON.stringify(result)).run();
 return {generated:true,location_id:env.LOCATION_ID,issued_at:issued,target_time:target,prediction:result,input_source:"Open-Meteo Best Match past hours only",verified_feedback_count:feedback.length};
}
export default {
 async fetch(request:Request,env:Env):Promise<Response>{
  const url=new URL(request.url);if(request.method==="OPTIONS")return json({});
  if(url.pathname==="/health")return json({ok:true,service:"templearn-cloud",inference:"TempLearn-62-weight",temperature_forecast_input:"disabled",input_history:"past_hours_only"});
  if(url.pathname==="/v1/forecasts"&&request.method==="GET"){
   const from=url.searchParams.get("from"),to=url.searchParams.get("to");
   if(!from||!to||!Number.isFinite(Date.parse(from))||!Number.isFinite(Date.parse(to)))return json({error:"Valid from and to ISO dates required"},400);
   const rows=await env.DB.prepare("SELECT location_id,issued_at,target_time,source,payload_json FROM forecast_vintages WHERE location_id=? AND target_time>=? AND target_time<=? ORDER BY issued_at ASC,target_time ASC LIMIT 2000").bind(env.LOCATION_ID,new Date(from).toISOString(),new Date(to).toISOString()).all();
   return json({location_id:env.LOCATION_ID,forecasts:(rows.results||[]).map((r:Record<string,unknown>)=>({location_id:r.location_id,issued_at:r.issued_at,target_time:r.target_time,source:r.source,prediction:JSON.parse(String(r.payload_json))}))});
  }
  if(url.pathname==="/v1/state"&&request.method==="POST"){
   if(!authorized(request,env))return json({error:"Unauthorized; configure SYNC_KEY secret"},401);
   await ensureSchema(env);
   let body:any;try{body=await request.json();}catch{return json({error:"Invalid JSON"},400);}
   if(!Array.isArray(body.weights)||body.weights.length!==62||!body.weights.every(Number.isFinite))return json({error:"Expected 62 finite model weights"},400);
   if(!Array.isArray(body.observations)||body.observations.length<3)return json({error:"Three hourly observations required"},400);
   if(body.feedback!==undefined&&!Array.isArray(body.feedback))return json({error:"feedback must be an array"},400);
   if(body.context!==undefined&&(typeof body.context!=="object"||body.context===null))return json({error:"context must be an object"},400);
   const obs=body.observations.slice(-3);
   if(obs[1].timestampSec-obs[0].timestampSec!==3600||obs[2].timestampSec-obs[1].timestampSec!==3600)return json({error:"Observations must be consecutive hourly readings"},400);
   await env.DB.prepare("INSERT INTO cloud_model_state (location_id,weights_json,observations_json,feedback_json,context_json,timezone,model_version,updated_at) VALUES (?,?,?,?,?,?,?,?) ON CONFLICT(location_id) DO UPDATE SET weights_json=excluded.weights_json,observations_json=excluded.observations_json,feedback_json=excluded.feedback_json,context_json=excluded.context_json,timezone=excluded.timezone,model_version=excluded.model_version,updated_at=excluded.updated_at")
    .bind(env.LOCATION_ID,JSON.stringify(body.weights),JSON.stringify(obs),JSON.stringify(body.feedback||[]),JSON.stringify(body.context||{}),String(body.timezone||env.TIMEZONE),String(body.modelVersion||"templearn-62-v1"),new Date().toISOString()).run();
   return json({ok:true,observations_received:3,model_version:body.modelVersion||"templearn-62-v1"});
  }
  if(url.pathname==="/v1/run"&&request.method==="POST"){
   if(!authorized(request,env))return json({error:"Unauthorized"},401);
   try{await ensureSchema(env);return json(await generate(env,Date.now()));}catch(e){return json({error:e instanceof Error?e.message:"Prediction failed"},422);}
  }
  return json({error:"Not found"},404);
 },
 async scheduled(event:ScheduledController,env:Env){await ensureSchema(env);await generate(env,event.scheduledTime);}
};
