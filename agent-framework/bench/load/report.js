/**
 * bench report — 汇总 results/ 下各场景档 summary → markdown 报告（见方案 §7.3）。
 *
 * 用法：node report.js [--results-dir ../results]
 * 产物：{results-dir}/report-{timestamp}.md
 */
'use strict';

const fs = require('fs');
const path = require('path');

const RESULTS_DIR = (() => {
  const i = process.argv.indexOf('--results-dir');
  return i > -1 ? process.argv[i + 1] : path.join(__dirname, '..', 'results');
})();

const SCENARIO_NAMES = {
  B0: '非沙箱纯文本（基线）',
  B1: '沙箱纯文本（固定开销）',
  B2: '沙箱+文件工具',
  B3: '沙箱+Shell',
  B4: '沙箱+MCP 工具',
  B5: '同用户并发（U=1 串行化上界）',
};

function pct(v) { return v == null ? '-' : v; }

function loadSummaries() {
  const out = {};
  for (const dir of fs.readdirSync(RESULTS_DIR, { withFileTypes: true })) {
    if (!dir.isDirectory() || !/^B\d$/.test(dir.name)) continue;
    out[dir.name] = [];
    for (const f of fs.readdirSync(path.join(RESULTS_DIR, dir.name)).sort((a, b) => {
      const na = parseInt(a, 10); const nb = parseInt(b, 10);
      return (isNaN(na) ? 0 : na) - (isNaN(nb) ? 0 : nb) || a.localeCompare(b);
    })) {
      if (!f.endsWith('.summary.json')) continue;
      try { out[dir.name].push(JSON.parse(fs.readFileSync(path.join(RESULTS_DIR, dir.name, f), 'utf8'))); } catch { /* 忽略损坏档 */ }
    }
  }
  return out;
}

/** 观测采样汇总：容器 CPU 峰值/均值、堆峰值、MySQL 连接峰值、沙箱数峰值 */
function observerSummary(scenario, stage) {
  const f = path.join(RESULTS_DIR, scenario, `${stage}.observers.jsonl`);
  if (!fs.existsSync(f)) return null;
  const rows = fs.readFileSync(f, 'utf8').split('\n').filter(Boolean).map((l) => {
    try { return JSON.parse(l); } catch { return {}; }
  });
  const pick = (k) => rows.map((r) => r[k]).filter((v) => typeof v === 'number');
  const cpu = pick('containerCpu');
  const heap = pick('heapUsedMb');
  const tc = pick('threadsConnected');
  const sb = pick('sandboxes').filter((v) => v !== null);
  return {
    cpuMax: cpu.length ? Math.max(...cpu) : null,
    cpuAvg: cpu.length ? +(cpu.reduce((a, b) => a + b, 0) / cpu.length).toFixed(0) : null,
    heapMaxMb: heap.length ? Math.max(...heap) : null,
    threadsMax: tc.length ? Math.max(...tc) : null,
    sandboxesMax: sb.length ? Math.max(...sb) : null,
  };
}

/** 结论行：最大支持并发（错误率<1% 且 P95≤10s 且未中止）、峰值 req/min、瓶颈初判 */
function conclusion(summaries) {
  let peakRpm = 0;
  let maxOk = null;
  let cpuSaturated = false;
  let poolCapped = false;
  for (const s of summaries) {
    const good = !s.aborted && s.errorRate != null && s.errorRate < 0.01 && s.p95Ms != null && s.p95Ms <= 10000;
    if (good) maxOk = s.concurrency;
    peakRpm = Math.max(peakRpm, s.reqPerMin || 0);
    const obs = observerSummary(s.scenario, s.stage);
    if (obs && obs.cpuMax != null && obs.cpuMax >= 95) cpuSaturated = true;
    if (s.concurrency > 10 && s.reqPerMin && s.reqPerMin <= summaries.find((x) => x.concurrency === 10)?.reqPerMin * 1.1) poolCapped = true;
  }
  const hints = [];
  if (cpuSaturated) hints.push('容器 CPU 持续 ≈100%（单核饱和）');
  if (poolCapped) hints.push('C>10 档吞吐停在池边界（用户池=10 结构性封顶，非缺陷）');
  if (!hints.length) hints.push('未观测到明显饱和点（详见各档观测数据）');
  return { maxOk, peakRpm, bottleneck: hints.join('；') };
}

function main() {
  const byScenario = loadSummaries();
  const lines = [];
  lines.push(`# Agent Framework 并发压测报告`);
  lines.push('');
  lines.push(`- 时间：${new Date().toISOString()}`);
  lines.push(`- 被测：docker bench-agent-fw（1C/1G 硬限，agent-framework:latest）`);
  lines.push(`- 口径：请求=POST /threads/{sid}/chat → AGENT_END；SLO：错误率<1% 且 P95≤10s；稳态 180s`);
  lines.push('');

  for (const sc of Object.keys(byScenario).sort()) {
    const summaries = byScenario[sc];
    if (!summaries.length) continue;
    lines.push(`## ${sc} ${SCENARIO_NAMES[sc] || ''}`);
    lines.push('');
    lines.push('| C | 成功 | 错误率 | req/min | RPS | P50(ms) | P95(ms) | P99(ms) | TTFT P50(ms) | waiting 均值 | 中止 | 容器CPU max% | 堆峰值MB | MySQL连接 max | 沙箱数 max |');
    lines.push('|---|------|--------|---------|-----|---------|---------|---------|--------------|--------------|------|--------------|----------|---------------|------------|');
    for (const s of summaries) {
      const obs = observerSummary(sc, s.stage);
      lines.push(`| ${s.concurrency} | ${s.ok} | ${s.errorRate == null ? '-' : (s.errorRate * 100).toFixed(1) + '%'} | ${s.reqPerMin} | ${s.rps} | ${pct(s.p50Ms)} | ${pct(s.p95Ms)} | ${pct(s.p99Ms)} | ${pct(s.ttftP50Ms)} | ${s.waitingAvg} | ${s.aborted ? '是(' + s.abortReason + ')' : '否'} | ${obs && obs.cpuMax != null ? obs.cpuMax : '-'} | ${obs && obs.heapMaxMb != null ? obs.heapMaxMb : '-'} | ${obs && obs.threadsMax != null ? obs.threadsMax : '-'} | ${obs && obs.sandboxesMax != null ? obs.sandboxesMax : '-'} |`);
    }
    // LLM 调用对账（最后档）
    const last = summaries[summaries.length - 1];
    lines.push('');
    lines.push(`- LLM 调用对账（末档 C=${last.concurrency}）：期望 ${last.llmCallsExpected} 次`);
    lines.push('');
    // ASCII 并发-吞吐曲线
    const maxRpm = Math.max(...summaries.map((s) => s.reqPerMin || 0), 1);
    lines.push('```');
    lines.push('并发-吞吐（req/min，1 字符 ≈ ' + Math.ceil(maxRpm / 40) + ' req/min）');
    for (const s of summaries) {
      const bar = '#'.repeat(Math.round((s.reqPerMin || 0) / Math.ceil(maxRpm / 40)));
      lines.push(`C=${String(s.concurrency).padStart(2)} | ${bar} ${s.reqPerMin}`);
    }
    lines.push('```');
    const c = conclusion(summaries);
    lines.push('');
    lines.push(`**结论**：最大支持并发 = ${c.maxOk ?? '无档达标'}，峰值 ${c.peakRpm} req/min，瓶颈初判 = ${c.bottleneck}`);
    lines.push('');
  }

  const outFile = path.join(RESULTS_DIR, `report-${new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19)}.md`);
  fs.writeFileSync(outFile, lines.join('\n'));
  console.log('[report] 写入 ' + outFile);
  console.log(lines.join('\n'));
}

main();
