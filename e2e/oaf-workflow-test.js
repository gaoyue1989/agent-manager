const puppeteer = require('puppeteer');
const http = require('http');

const BASE_URL = process.env.BASE_URL || 'http://localhost:8911';
const API_URL = 'http://localhost:8080/api/v1';

async function request(method, path, body = null) {
  return new Promise((resolve, reject) => {
    const url = new URL(path, API_URL);
    const options = {
      hostname: url.hostname,
      port: url.port || 80,
      path: url.pathname + url.search,
      method,
      headers: { 'Content-Type': 'application/json' },
    };
    const req = http.request(options, (res) => {
      let data = '';
      res.on('data', (chunk) => (data += chunk));
      res.on('end', () => {
        if (res.statusCode >= 400) {
          reject(new Error(`HTTP ${res.statusCode}: ${data}`));
        } else {
          resolve(data ? JSON.parse(data) : {});
        }
      });
    });
    req.on('error', reject);
    if (body) req.write(JSON.stringify(body));
    req.end();
  });
}

function log(name, status, desc = '') {
  console.log(`[${status === 'PASS' ? '✓' : '✗'}] ${name}${desc ? ': ' + desc : ''}`);
}

async function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
}

async function main() {
  console.log('=== OAF Workflow E2E Tests ===\n');

  const browser = await puppeteer.launch({ headless: 'new' });
  const page = await browser.newPage();
  const createdAgents = [];

  try {
    // Test 1: Create agent with minimal OAF
    console.log('\n--- Test 1: Create agent with minimal OAF ---');
    const minimalOAF = `---
name: "Minimal Test Agent"
vendorKey: "e2e"
agentKey: "minimal"
version: "1.0.0"
slug: "e2e/minimal"
description: "E2E test agent with minimal config"
author: "@e2e"
license: "MIT"
tags: ["e2e", "test"]
---
You are a minimal test agent.`;

    const agent1 = await request('POST', '/agents', { config: minimalOAF, config_type: 'oaf' });
    createdAgents.push(agent1.id);
    log('Create minimal OAF agent', 'PASS', `ID: ${agent1.id}`);
    
    // Verify config_type is oaf
    const fetched1 = await request('GET', `/agents/${agent1.id}`);
    if (fetched1.config_type === 'oaf') {
      log('Config type is oaf', 'PASS');
    } else {
      log('Config type is oaf', 'FAIL', `got: ${fetched1.config_type}`);
    }
    
    // Verify name extracted from OAF
    if (fetched1.name === 'Minimal Test Agent') {
      log('Name extracted from OAF', 'PASS');
    } else {
      log('Name extracted from OAF', 'FAIL', `got: ${fetched1.name}`);
    }

    // Test 2: Create agent with full OAF
    console.log('\n--- Test 2: Create agent with full OAF ---');
    const fullOAF = `---
name: "Full Test Agent"
vendorKey: "e2e"
agentKey: "full"
version: "1.0.0"
slug: "e2e/full"
description: "E2E test agent with full config"
author: "@e2e"
license: "MIT"
tags: ["e2e", "test", "full"]

skills:
  - name: "web-search"
    source: "local"
    version: "1.0.0"
    required: true

mcpServers:
  - vendor: "block"
    server: "filesystem"
    version: "1.0.0"
    configDir: "mcp-configs/filesystem"
    required: true

tools: ["Read", "Edit", "Bash", "Glob", "Grep"]

config:
  temperature: 0.7
  max_tokens: 4096
---
# Agent Purpose

You are a test agent for E2E testing.

## Core Responsibilities

- Test OAF configuration
- Verify code generation`;

    const agent2 = await request('POST', '/agents', { config: fullOAF, config_type: 'oaf' });
    createdAgents.push(agent2.id);
    log('Create full OAF agent', 'PASS', `ID: ${agent2.id}`);

    // Test 3: Generate code from OAF
    console.log('\n--- Test 3: Generate code from OAF ---');
    try {
      const gen = await request('POST', `/agents/${agent2.id}/generate`);
      if (gen.status === 'success') {
        log('Generate code from OAF', 'PASS');
      } else {
        log('Generate code from OAF', 'FAIL', `status: ${gen.status}`);
      }
    } catch (e) {
      log('Generate code from OAF', 'FAIL', e.message);
    }

    // Test 4: View OAF config in detail page
    console.log('\n--- Test 4: View OAF config in frontend ---');
    await page.goto(`${BASE_URL}/agents/${agent2.id}`, { waitUntil: 'networkidle0' });
    await sleep(2000);
    
    const pageContent = await page.content();
    if (pageContent.includes('Full Test Agent')) {
      log('Detail page shows agent name', 'PASS');
    } else {
      log('Detail page shows agent name', 'FAIL');
    }
    
    if (pageContent.includes('e2e/full')) {
      log('Detail page shows slug', 'PASS');
    } else {
      log('Detail page shows slug', 'FAIL');
    }

    // Test 5: Update OAF config
    console.log('\n--- Test 5: Update OAF config ---');
    const updatedOAF = fullOAF.replace('version: "1.0.0"', 'version: "1.1.0"');
    const updated = await request('PUT', `/agents/${agent2.id}`, { config: updatedOAF });
    if (updated.version === 2) {
      log('Update OAF config', 'PASS', 'version incremented');
    } else {
      log('Update OAF config', 'FAIL', `version: ${updated.version}`);
    }

    // Test 6: Delete OAF agents
    console.log('\n--- Test 6: Delete OAF agents ---');
    for (const id of createdAgents) {
      try {
        const result = await request('DELETE', `/agents/${id}`);
        if (result.message === 'deleted') {
          log(`Delete agent ${id}`, 'PASS');
        } else {
          log(`Delete agent ${id}`, 'FAIL');
        }
      } catch (e) {
        log(`Delete agent ${id}`, 'FAIL', e.message);
      }
    }

    // Test 7: Verify agents deleted
    console.log('\n--- Test 7: Verify agents deleted ---');
    for (const id of createdAgents) {
      try {
        await request('GET', `/agents/${id}`);
        log(`Agent ${id} should be deleted`, 'FAIL', 'still exists');
      } catch (e) {
        if (e.message.includes('404')) {
          log(`Agent ${id} deleted verified`, 'PASS');
        } else {
          log(`Agent ${id} deleted verified`, 'FAIL', e.message);
        }
      }
    }

    console.log('\n=== All tests completed ===');
  } catch (e) {
    console.error('Test error:', e);
  } finally {
    await browser.close();
  }
}

main().catch(console.error);
