import { createInterface } from 'node:readline';
import { Readable, Writable } from 'node:stream';

export type TerminalInput = Readable & { isTTY?: boolean };
function readTerminal(prompt: string, input: TerminalInput, output: Writable, visible: boolean): Promise<string> {
  if (!input.isTTY) return Promise.reject(new Error('请在交互终端输入凭据；不接受管道中的令牌'));
  return new Promise((resolve,reject) => {
    let settled = false;
    const sink = visible ? output : new Writable({ write(_chunk,_encoding,callback) { callback(); } });
    const line = createInterface({ input, output: sink, terminal: true, historySize: 0 });
    const label = (visible ? '【输入可见】' : '【输入隐藏】') + prompt;
    if (!visible) output.write(label);
    const endSink = () => { if (!visible) sink.end(); };
    const cancelled = () => {
      if (settled) return;
      settled = true; line.close(); output.write('\n'); endSink(); reject(new Error('已取消凭据输入'));
    };
    line.once('SIGINT', cancelled);
    line.once('close', cancelled);
    line.question(visible ? label : '', value => {
      if (settled) return;
      settled = true; line.close();
      if (!visible) output.write('\n');
      endSink(); resolve(value);
    });
  });
}
export function readSecret(prompt: string, input: TerminalInput = process.stdin, output: Writable = process.stdout): Promise<string> {
  return readTerminal(prompt,input,output,false);
}
export function readVisible(prompt: string, input: TerminalInput = process.stdin, output: Writable = process.stdout): Promise<string> {
  return readTerminal(prompt,input,output,true);
}
export function selectPrompt(args: readonly string[]): typeof readSecret {
  if (args.length === 0) return readSecret;
  if (args.length === 1 && args[0] === '--show-input') return readVisible;
  throw new Error('仅支持可选参数 --show-input');
}
