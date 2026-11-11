const {chromium}=require('playwright');const assert=require('node:assert/strict');
(async()=>{const browser=await chromium.launch({executablePath:process.env.CHROMIUM_PATH||'/usr/bin/chromium',headless:true});try{
const context=await browser.newContext({viewport:{width:1440,height:1000}});const page=await context.newPage();const errors=[];page.on('pageerror',e=>errors.push(e.message));await page.goto(process.env.AGENT_URL||'http://127.0.0.1:18081');
let release,started;const seen=new Promise(r=>started=r),gate=new Promise(r=>release=r);let held=false;
await page.route('**/api/memory/*',async route=>{if(held)return route.continue();held=true;started();await gate;await route.fulfill({json:{longTermMemory:{'local.product_query':'"PRIVATE_PREVIOUS_USER"'}}});});
let historyHeld=false;
await page.route('**/api/conversations/**',async route=>{if(historyHeld)return route.continue();historyHeld=true;await gate;await route.fulfill({json:{messages:[{role:'user',content:'PRIVATE_PREVIOUS_HISTORY'}]}});});
await page.locator('.profile').first().click();await seen;
await page.getByRole('button',{name:'退出',exact:true}).click();await page.locator('.profile').nth(1).click();await page.locator('#app').waitFor({state:'visible'});const newIdentity=await page.locator('#who').innerText();release();await page.waitForTimeout(250);
assert.doesNotMatch(await page.locator('#memory').innerText(),/PRIVATE_PREVIOUS_USER/);assert.doesNotMatch(await page.locator('#messages').innerText(),/PRIVATE_PREVIOUS_HISTORY/);assert.equal(await page.locator('#who').innerText(),newIdentity);
// Drop the first completed response after the server committed it, then retry.
await page.getByRole('button',{name:'＋ 新建对话'}).click();
await page.waitForFunction(()=>document.getElementById('messages').innerText==='');
await page.evaluate(()=>{const Native=window.WebSocket;let dropped=false;window.WebSocket=class extends Native{constructor(...args){super(...args);this.addEventListener('message',event=>{if(!dropped&&JSON.parse(event.data).event==='agent_completed'){dropped=true;event.stopImmediatePropagation();this.close();}});}};});
await page.locator('#input').fill('北区咖啡预算20元');await page.locator('#send').click();await page.locator('#retry').waitFor({state:'visible'});
const first=await page.evaluate(()=>JSON.parse(localStorage.getItem(Object.keys(localStorage).find(k=>k.startsWith('mini-reco-local-pending:')))));
await page.locator('#retry').click();await page.waitForFunction(()=>document.getElementById('status').innerText.includes('已恢复上次任务结果'));
assert.ok(first.request_id);assert.equal(await page.evaluate(()=>localStorage.getItem(Object.keys(localStorage).find(k=>k.startsWith('mini-reco-local-pending:')))),null);
await page.reload();await page.locator('#app').waitFor({state:'visible'});assert.equal(await page.locator('#who').innerText(),newIdentity);assert.deepEqual(errors,[]);
const other=await page.context().newPage();await other.goto(process.env.AGENT_URL||'http://127.0.0.1:18081');await other.locator('#app').waitFor({state:'visible'});
await other.getByRole('button',{name:'退出',exact:true}).click();await page.locator('#login').waitFor({state:'visible'});assert.equal(await page.locator('#app').isVisible(),false);await other.close();

console.log('PASS desktop 1440px: late prior-user memory isolation, refresh restores identity, no JS errors');
}finally{await browser.close();}})().catch(e=>{console.error(e);process.exitCode=1});
