// Requires Playwright and Chromium; uses only the public synthetic demo.
const { chromium } = require('playwright');
const assert = require('node:assert/strict');
(async () => {
  const browser = await chromium.launch({
    ...(process.env.CHROMIUM_PATH ? {executablePath: process.env.CHROMIUM_PATH} : {}),
    headless: true,
  });
  try {
    const page = await browser.newPage();
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.goto(process.env.AGENT_URL || 'http://127.0.0.1:18081');
    await page.locator('.profile').first().click();
    await page.locator('#app').waitFor({state: 'visible'});
    await page.locator('#input').fill('北区拿铁预算30元');
    await page.locator('#send').click();
    await page.locator('.quote-total').first().waitFor();
    assert.match(await page.locator('.quote-total').first().innerText(), /23\.00/);
    assert.match(await page.locator('#constraints').innerText(), /商品：拿铁/);
    assert.doesNotMatch(await page.locator('.recommendations').innerText(), /美式/);
    // Keep one response pending while the user creates a new conversation.
    await page.evaluate(() => {
      window.WebSocket = class {
        constructor() { window.delayedSocket = this; queueMicrotask(() => this.onopen()); }
        send() {}
        close() { this.onclose?.(); }
      };
    });
    await page.locator('#input').fill('预算改为20元');
    await page.locator('#send').click();
    const before = await page.locator('#session').innerText();
    await page.getByRole('button', {name: '＋ 新建对话'}).click();
    await page.waitForFunction(old => document.getElementById('session').innerText !== old, before);
    await page.evaluate(() => window.delayedSocket.onmessage({data: JSON.stringify({
      event: 'agent_completed', data: {answer: 'STALE_RESPONSE_MUST_NOT_APPEAR', items: [], status: 'completed'},
    })}));
    assert.equal(await page.locator('#messages').innerText(), '');
    assert.equal(await page.locator('#send').isEnabled(), true);
    assert.deepEqual(errors, []);
    console.log('PASS: named-product quote, cost card, constraint display, delayed response isolation');
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
