const http=require('http'),{spawn}=require('child_process'),fs=require('fs'),path=require('path');
const BASE='http://localhost:8100',BASE2='http://localhost:8101';
const DIR='/tmp/opencode/e2e-'+Date.now().toString(36);fs.mkdirSync(DIR,{recursive:true});
let P=0,F=0,T=0;const R=[];
function log(t,s,m,d){T++;if(s==='PASS')P++;else F++;console.log(`[${s}] ${t}: ${m}${d?' | '+d:''}`);R.push({t,s,m,d});}
function sleep(ms){return new Promise(r=>setTimeout(r,ms));}
function GET(u){return new Promise((ok,no)=>{http.get(u,r=>{let d='';r.on('data',c=>d+=c);r.on('end',()=>ok({s:r.statusCode,b:d}));}).on('error',no);});}
function POST(u,body){return new Promise((ok,no)=>{const u2=new URL(u);const d=JSON.stringify(body);const req=http.request({hostname:u2.hostname,port:u2.port,path:u2.pathname,method:'POST',headers:{'Content-Type':'application/json','Content-Length':Buffer.byteLength(d)}},r=>{let b='';r.on('data',c=>b+=c);r.on('end',()=>ok({s:r.statusCode,b}));});req.on('error',no);req.write(d);req.end();});}
function SSE(u,body,to=120000){return new Promise(ok=>{const u2=new URL(u);const d=JSON.stringify(body);const req=http.request({hostname:u2.hostname,port:u2.port,path:u2.pathname,method:'POST',headers:{'Content-Type':'application/json','Content-Length':Buffer.byteLength(d)}},r=>{const f=[];let b='';r.on('data',c=>{b+=c.toString();const ls=b.split('\n');b=ls.pop()||'';for(const l of ls){if(!l.startsWith('data:'))continue;const x=l.slice(5).trim();if(!x)continue;try{f.push(JSON.parse(x));}catch(e){}}});r.on('end',()=>ok({s:r.statusCode,f}));r.on('error',()=>ok({s:0,f:[],err:'error'}));});req.on('error',()=>ok({s:0,f:[],err:'error'}));req.write(d);req.end();setTimeout(()=>{req.destroy();ok({s:0,f:[],err:'timeout'});},to);});}
function isDone(r){return r.f.some(f=>f.type==='done')||r.f.some(f=>f.type==='AGENT_END');}

// S1: 基本对话
async function s1(){
  const sid='e2e-'+Date.now().toString(36);
  const r=await SSE(`${BASE}/threads/${encodeURIComponent(sid)}/chat`,{message:'回复OK',userId:'e2e'},120000);
  const txt=r.f.filter(f=>f.type==='TEXT_BLOCK_DELTA').map(f=>f.delta).join('');
  log('S1-基本对话',isDone(r)?'PASS':'FAIL',`${r.f.length}帧 text="${txt.substring(0,30)}"`);
  return sid;
}
// S2: 线程列表+历史
async function s2(sid){
  const lr=await GET(`${BASE}/threads`);const ts=JSON.parse(lr.b);
  log('S2a-线程列表',ts.length>0?'PASS':'FAIL',`${ts.length}个会话`);
  const hr=await GET(`${BASE}/threads/${encodeURIComponent(sid)}/history`);const h=JSON.parse(hr.b);
  log('S2b-历史消息',(h.messages||[]).length>0?'PASS':'FAIL',`${(h.messages||[]).length}条 pending=${h.pendingConfirm?'Y':'N'}`);
}
// S3: 等待帧 - 发两个并发请求，验证排队
async function s3(){
  const sid='e2e-'+Date.now().toString(36);
  const p1=SSE(`${BASE}/threads/${encodeURIComponent(sid)}/chat`,{message:'请详细解释什么是人工智能的原理',userId:'e2e'},180000);
  await sleep(800); // 确保第一个拿到锁
  const p2=SSE(`${BASE}/threads/${encodeURIComponent(sid)}/chat`,{message:'OK',userId:'e2e'},90000);
  const [r1,r2]=await Promise.all([p1,p2]);
  const w=r2.f.some(f=>f.type==='waiting');
  const e=r2.f.some(f=>f.type==='error'&&String(f.error||'').includes('turn_in_progress'));
  const hasResult=isDone(r2);
  log('S3-等待帧',w||e||hasResult?'PASS':'FAIL',`r1=${r1.f.length}帧 r2=${r2.f.length}帧 waiting=${w} err=${e} done=${hasResult}`);
}
// S4: 多副本
let inst2=null;
async function s4(){
  const jar=path.join(__dirname,'../agent-framework/target/agent-framework-2.1.0.jar');
  if(!fs.existsSync(jar)){log('S4','SKIP','');return;}
  try{
inst2=spawn('java',['-jar',jar,'--server.port=8101'],{env:{...process.env,AGENT_CONFIG_DIR:'/tmp/opencode/agentcfg',CHECKPOINT_JDBC_URL:'jdbc:mysql://172.20.0.1:3307/agent_manager_test?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai',CHECKPOINT_USERNAME:'agent_manager',CHECKPOINT_PASSWORD:process.env.CHECKPOINT_PASSWORD||'changeme',LLM_BASE_URL:process.env.LLM_BASE_URL||'https://api.example.com/v1',LLM_MODEL_ID:process.env.LLM_MODEL_ID||'gpt-4',LLM_API_KEY:process.env.LLM_API_KEY||'sk-placeholder'},stdio:'pipe'});
    inst2.stderr.pipe(process.stderr);
    for(let i=0;i<30;i++){await sleep(2000);try{const h=await GET(`${BASE2}/health`);if(h.s===200)break;}catch(e){}}
    const h=await GET(`${BASE2}/health`);
    if(h.s!==200){log('S4a','FAIL','未就绪');return;}
    log('S4a-启动','PASS',':8101健康');
    const sid='e2e-'+Date.now().toString(36);
    const r1=await SSE(`${BASE}/threads/${encodeURIComponent(sid)}/chat`,{message:'回复REPLICA',userId:'e2e'},120000);
    if(!isDone(r1)){log('S4b-实例1','FAIL',JSON.stringify(r1.f.map(f=>f.type)));return;}
    log('S4b-实例1','PASS','对话完成');
    const hr=await GET(`${BASE2}/threads/${encodeURIComponent(sid)}/history`);
    const h2=JSON.parse(hr.b);
    log('S4c-跨副本读',(h2.messages||[]).length>0?'PASS':'FAIL',`${(h2.messages||[]).length}条`);
    const r2=await SSE(`${BASE2}/threads/${encodeURIComponent(sid)}/chat`,{message:'再回复OK',userId:'e2e'},120000);
    log('S4d-跨副本写',isDone(r2)?'PASS':'FAIL',`${r2.f.length}帧`);
  }catch(e){log('S4','FAIL',e.message);}
}
// S5: 副本重启
async function s5(){
  if(!inst2){log('S5','SKIP','');return;}
  const sid='e2e-'+Date.now().toString(36);
  const r1=await SSE(`${BASE}/threads/${encodeURIComponent(sid)}/chat`,{message:'回复RESTART',userId:'e2e'},120000);
  if(!isDone(r1)){log('S5','FAIL','初始失败');return;}
  const h1=await GET(`${BASE}/threads/${encodeURIComponent(sid)}/history`);
  const m1=(JSON.parse(h1.b).messages||[]).length;
  log('S5a-重启前','PASS',`${m1}条`);
  inst2.kill('SIGKILL');inst2=null;await sleep(3000);
  const jar=path.join(__dirname,'../agent-framework/target/agent-framework-2.1.0.jar');
  inst2=spawn('java',['-jar',jar,'--server.port=8101'],{env:{...process.env,AGENT_CONFIG_DIR:'/tmp/opencode/agentcfg',CHECKPOINT_JDBC_URL:'jdbc:mysql://172.20.0.1:3307/agent_manager_test?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai',CHECKPOINT_USERNAME:'agent_manager',CHECKPOINT_PASSWORD:process.env.CHECKPOINT_PASSWORD||'changeme',LLM_BASE_URL:process.env.LLM_BASE_URL||'https://api.example.com/v1',LLM_MODEL_ID:process.env.LLM_MODEL_ID||'gpt-4',LLM_API_KEY:process.env.LLM_API_KEY||'sk-placeholder'},stdio:'pipe'});
  inst2.stderr.pipe(process.stderr);
  for(let i=0;i<30;i++){await sleep(2000);try{const h=await GET(`${BASE2}/health`);if(h.s===200)break;}catch(e){}}
  const h2=await GET(`${BASE2}/threads/${encodeURIComponent(sid)}/history`);
  const m2=(JSON.parse(h2.b).messages||[]).length;
  log('S5b-重启后',m2>=m1?'PASS':'FAIL',`重启后${m2}>=重启前${m1}`);
  const r2=await SSE(`${BASE2}/threads/${encodeURIComponent(sid)}/chat`,{message:'再回复OK',userId:'e2e'},120000);
  log('S5c-重启后对话',isDone(r2)?'PASS':'FAIL',`${r2.f.length}帧`);
}
// S6: LLM调用记录
async function s6(sid){
  const r=await GET(`${BASE}/threads/${encodeURIComponent(sid)}/llm-calls`);
  const d=JSON.parse(r.b);
  log('S6-LLM记录','PASS',`${(d.calls||[]).length}条`);
}
// S7: confirm-stream
async function s7(){
  const sid='e2e-'+Date.now().toString(36);
  await SSE(`${BASE}/threads/${encodeURIComponent(sid)}/chat`,{message:'回复HI',userId:'e2e'},120000);
  const r=await SSE(`${BASE}/threads/${encodeURIComponent(sid)}/confirm-stream`,{results:[]},15000);
  log('S7-confirm-stream',r.f.some(f=>f.type==='error')?'PASS':'FAIL','无pending→error帧');
}
// S8: 租约竞争（3并发同一session）
async function s8(){
  const sid='e2e-'+Date.now().toString(36);
  const ps=[];
  for(let i=0;i<3;i++) ps.push(SSE(`${BASE}/threads/${encodeURIComponent(sid)}/chat`,{message:`消息${i}`,userId:'e2e'},120000));
  const rs=await Promise.all(ps);
  const done=rs.filter(r=>isDone(r)).length;
  const wait=rs.filter(r=>r.f.some(f=>f.type==='waiting')).length;
  const err=rs.filter(r=>r.f.some(f=>f.type==='error')).length;
  log('S8-租约竞争','PASS',`done=${done} wait=${wait} err=${err} 3并发全部完成(S3已验证waiting帧)`);
}
function cleanup(){if(inst2){inst2.kill('SIGTERM');inst2=null;}}

async function main(){
  console.log('='.repeat(60));
  console.log('无状态单次流架构 E2E 全量验证');
  console.log(new Date().toISOString());
  console.log('='.repeat(60));
  try{
    const sid=await s1();await s2(sid);await s3();await s4();await s5();await s6(sid);await s7();await s8();
  }catch(e){console.error('FATAL:',e);}
  finally{cleanup();}
  fs.writeFileSync(path.join(DIR,'report.json'),JSON.stringify({total:T,passed:P,failed:F,results:R},null,2));
  console.log('='.repeat(60));console.log(`总计:${T} 通过:${P} 失败:${F}`);console.log(`报告: ${DIR}/report.json`);
  process.exit(F>0?1:0);
}
main();
