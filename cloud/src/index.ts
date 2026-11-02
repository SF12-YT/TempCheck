import { predict } from "./inference";
interface Env { DB: D1Database; LOCATION_ID: string; TIMEZONE: string; SYNC_KEY?: string; }
async function ensureSchema(env:Env) {
 await env.DB.prepare("CREATE TABLE IF NOT EXISTS cloud_model_state (location_id TEXT PRIMARY KEY NOT NULL, weights_json TEXT NOT NULL, observations_json TEXT NOT NULL, feedback_json TEXT NOT NULL, context_json TEXT NOT NULL, timezone TEXT NOT NULL, model_version TEXT NOT NULL, updated_at TEXT NOT NULL)").run();
}
function authorized(request:Request,env:Env):boolean {
 return !!env.SYNC_KEY && request.headers.get("x-templearn-key")===env.SYNC_KEY;
}
function json(data: unknown, status=200) { return new Response(JSON.stringify(data), {status,headers:{"content-type":"application/json","access-control-allow-origin":"*","cache-control":"no-store"}}); }
async function generate(env:Env, issuedMs:number) {
 const row=await env.DB.prepare("SELECT weights_json, observations_json, feedback_json, context_json, timezone, model_version FROM cloud_model_state WHERE location_id=?").bind(env.LOCATION_ID).first<Record<string,unknown>>();
 if(!row) return {generated:false,reason:"No model state uploaded"};
 const issuedSec=Math.floor(issuedMs/1000), targetSec=(Math.floor(issuedMs/3600000)+1)*3600;
 const result=predict({weights:JSON.parse(String(row.weights_json)),observations:JSON.parse(String(row.observations_json)),feedback:JSON.parse(String(row.feedback_json)),context:JSON.parse(String(row.context_json)),timezone:String(row.timezone||env.TIMEZONE),targetTimestampSec:targetSec,predictionMadeAtSec:issuedSec,modelVersion:String(row.model_version)});
 const issued=new Date(issuedMs).toISOString(), target=new Date(targetSec*1000).toISOString();
 await env.DB.prepare("INSERT OR IGNORE INTO forecast_vintages (location_id,issued_at,target_time,source,payload_json) VALUES (?,?,?,'templearn-algorithm',?)").bind(env.LOCATION_ID,issued,target,JSON.stringify(result)).run();
 return {generated:true,issued_at:issued,target_time:target,prediction:result};
}
export default {
 async fetch(request:Request,env:Env):Promise<Response>{
  const url=new URL(request.url);
  if(request.method==="OPTIONS") return json({});
  if(url.pathname==="/health") return json({ok:true,service:"templearn-cloud",inference:"TempLearn-62-weight"});
  if(url.pathname==="/v1/forecasts"&&request.method==="GET"){
   const from=url.searchParams.get("from"),to=url.searchParams.get("to");
   if(!from||!to||!Number.isFinite(Date.parse(from))||!Number.isFinite(Date.parse(to))) return json({error:"Valid from and to ISO dates required"},400);
   const rows=await env.DB.prepare("SELECT location_id,issued_at,target_time,source,payload_json FROM forecast_vintages WHERE location_id=? AND target_time>=? AND target_time<=? ORDER BY issued_at ASC,target_time ASC LIMIT 2000").bind(env.LOCATION_ID,new Date(from).toISOString(),new Date(to).toISOString()).all();
   return json({location_id:env.LOCATION_ID,forecasts:(rows.results||[]).map((r:Record<string,unknown>)=>({location_id:r.location_id,issued_at:r.issued_at,target_time:r.target_time,source:r.source,prediction:JSON.parse(String(r.payload_json))}))});
  }
  if(url.pathname==="/v1/state"&&request.method==="POST"){
   if(!authorized(request,env)) return json({error:"Unauthorized; configure SYNC_KEY in Worker secrets"},401);
   await ensureSchema(env);
   let body:any; try{body=await request.json();}catch{return json({error:"Invalid JSON"},400);}
   if(!Array.isArray(body.weights)||body.weights.length!==62||!body.weights.every(Number.isFinite)) return json({error:"Expected 62 finite model weights"},400);
   if(!Array.isArray(body.observations)||body.observations.length<3) return json({error:"Three hourly observations required"},400);
   if(body.feedback!==undefined&&!Array.isArray(body.feedback)) return json({error:"feedback must be an array"},400);
   if(body.context!==undefined&&(typeof body.context!=="object"||body.context===null)) return json({error:"context must be an object"},400);
   const obs=body.observations.slice(-3);
   if(obs[1].timestampSec-obs[0].timestampSec!==3600||obs[2].timestampSec-obs[1].timestampSec!==3600) return json({error:"Observations must be consecutive hourly readings"},400);
   await env.DB.prepare("INSERT INTO cloud_model_state (location_id,weights_json,observations_json,feedback_json,context_json,timezone,model_version,updated_at) VALUES (?,?,?,?,?,?,?,?) ON CONFLICT(location_id) DO UPDATE SET weights_json=excluded.weights_json,observations_json=excluded.observations_json,feedback_json=excluded.feedback_json,context_json=excluded.context_json,timezone=excluded.timezone,model_version=excluded.model_version,updated_at=excluded.updated_at")
    .bind(env.LOCATION_ID,JSON.stringify(body.weights),JSON.stringify(obs),JSON.stringify(body.feedback||[]),JSON.stringify(body.context||{}),String(body.timezone||env.TIMEZONE),String(body.modelVersion||"templearn-62-v1"),new Date().toISOString()).run();
   return json({ok:true,observations_received:3,model_version:body.modelVersion||"templearn-62-v1"});
  }
  if(url.pathname==="/v1/run"&&request.method==="POST") {if(!authorized(request,env)) return json({error:"Unauthorized"},401); try{await ensureSchema(env);return json(await generate(env,Date.now()));}catch(e){return json({error:e instanceof Error?e.message:"Prediction failed"},422);}}
  return json({error:"Not found"},404);
 },
 async scheduled(event:ScheduledController,env:Env){await ensureSchema(env);await generate(env,event.scheduledTime);}
};
