import test from 'node:test';
import assert from 'node:assert/strict';
import * as fs from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createHarness } from './ui-harness.mjs';

test('sequential harness instances preserve every observed checkpoint', async () => {
  const workDir = await fs.mkdtemp(join(tmpdir(), 'yingji-harness-record-'));
  try {
    const args = { fs, workDir, adb: 'unused', serial: 'unused', run: async () => { throw new Error('Unexpected device call'); } };
    const first = await createHarness(args);
    const second = await createHarness(args);
    await first.record('first observer');
    await second.record('second observer');
    await first.record('first observer again');
    const saved = JSON.parse(await fs.readFile(join(workDir, 'ui-checks.json'), 'utf8'));
    assert.deepEqual(saved.map(row => row.label), ['first observer', 'second observer', 'first observer again']);
    assert.equal(saved.length, 3);
    assert.ok(saved.every(row => row.passed === true && Number.isFinite(Date.parse(row.time))));
  } finally {
    await fs.unlink(join(workDir, 'ui-checks.json')).catch(error => { if (error.code !== 'ENOENT') throw error; });
    await fs.rmdir(workDir);
  }
});
