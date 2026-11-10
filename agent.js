#!/usr/bin/env node
// QwenNodeAgent — Ollama (qwen2.5:7b) tabanlı yerel dosya ajanı.
// Bağımlılık yok; Node.js 18+ yeterli.
'use strict';

const fs = require('fs');
const path = require('path');
const readline = require('readline');

const HOST = process.env.OLLAMA_HOST_URL || 'http://127.0.0.1:11434';
const MODEL = process.env.QWEN_MODEL || 'qwen2.5:7b';
const NUM_CTX = Number(process.env.QWEN_NUM_CTX || 4096);
const TEMPERATURE = Number(process.env.QWEN_TEMPERATURE || 0.3);
const ROOT = path.resolve(process.env.QWEN_WORKDIR || process.cwd());
const MAX_STEPS = 6; // tek kullanıcı istemi için azami otonom tur
const MAX_FILE_CHARS = 6000; // bağlamı (4096 token) taşırmamak için okuma sınırı
const MAX_HISTORY_CHARS = 9000;

const SYSTEM_PROMPT = `Sen yerel dosyaları okuma, yazma ve düzenleme yetkisine sahip kıdemli bir yazılım ajanısın. Dosya işlemleri için [WRITE: path]...[/WRITE], [READ: path], [LIST: path] etiketlerini kullanırsın.

Kurallar:
- Dosya yazmak için tam içeriği şu biçimde ver:
[WRITE: dosya_yolu]
...icerik...
[/WRITE]
- Dosya okumak için: [READ: dosya_yolu]
- Dizin listelemek için: [LIST: dizin_yolu]
- Yollar çalışma dizinine göredir: ${ROOT}
- Var olan bir dosyayı düzenlemeden önce onu [READ] ile oku, sonra tamamını [WRITE] ile yeniden yaz.
- Etiket sonuçları sana "SİSTEM NOTU" olarak iletilir. İş bittiğinde etiket kullanmadan kısa bir özet yaz.`;

const history = [{ role: 'system', content: SYSTEM_PROMPT }];

// Çalışma dizininin dışına çıkan yolları reddeder.
function resolveSafe(p) {
  const full = path.resolve(ROOT, p.trim());
  if (full !== ROOT && !full.startsWith(ROOT + path.sep)) {
    throw new Error(`Çalışma dizini dışına erişim engellendi: ${p}`);
  }
  return full;
}

// Model içeriği ``` çitiyle sardıysa çiti atar.
function stripFence(body) {
  const m = body.match(/^\s*```[^\n]*\n([\s\S]*?)\n?```\s*$/);
  return m ? m[1] : body;
}

// Yanıttaki etiketleri çalıştırır; her işlem için bir sonuç satırı döndürür.
function runTools(reply) {
  const results = [];
  const rest = reply.replace(
    /\[WRITE:\s*([^\]\n]+)\]\r?\n?([\s\S]*?)\[\/WRITE\]/g,
    (_, file, body) => {
      try {
        const full = resolveSafe(file);
        fs.mkdirSync(path.dirname(full), { recursive: true });
        const content = stripFence(body).replace(/\s+$/, '') + '\n';
        fs.writeFileSync(full, content, 'utf8');
        results.push(`WRITE ${file.trim()}: başarılı (${content.length} karakter)`);
      } catch (e) {
        results.push(`WRITE ${file.trim()}: HATA - ${e.message}`);
      }
      return '';
    }
  );

  for (const m of rest.matchAll(/\[READ:\s*([^\]\n]+)\]/g)) {
    const file = m[1].trim();
    try {
      let text = fs.readFileSync(resolveSafe(file), 'utf8');
      if (text.length > MAX_FILE_CHARS) {
        text = text.slice(0, MAX_FILE_CHARS) + `\n...[kesildi, toplam ${text.length} karakter]`;
      }
      results.push(`READ ${file} içeriği:\n${text}`);
    } catch (e) {
      results.push(`READ ${file}: HATA - ${e.message}`);
    }
  }

  for (const m of rest.matchAll(/\[LIST:\s*([^\]\n]+)\]/g)) {
    const dir = m[1].trim();
    try {
      const items = fs
        .readdirSync(resolveSafe(dir), { withFileTypes: true })
        .map((d) => (d.isDirectory() ? d.name + '/' : d.name));
      results.push(`LIST ${dir}:\n${items.join('\n') || '(boş)'}`);
    } catch (e) {
      results.push(`LIST ${dir}: HATA - ${e.message}`);
    }
  }
  return results;
}

// Sistem istemi sabit kalır; bağlam dolunca en eski turlar atılır.
function trimHistory() {
  const size = () => history.reduce((n, m) => n + m.content.length, 0);
  while (history.length > 3 && size() > MAX_HISTORY_CHARS) history.splice(1, 1);
}

// Ollama'ya akışlı (streaming) istek atar, yanıtı yazdırıp döndürür.
async function chat() {
  const res = await fetch(`${HOST}/api/chat`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      model: MODEL,
      messages: history,
      stream: true,
      options: { num_ctx: NUM_CTX, temperature: TEMPERATURE },
    }),
  });
  if (!res.ok) throw new Error(`Ollama ${res.status}: ${await res.text()}`);

  let full = '';
  let buf = '';
  const decoder = new TextDecoder();
  for await (const chunk of res.body) {
    buf += decoder.decode(chunk, { stream: true });
    const lines = buf.split('\n');
    buf = lines.pop();
    for (const line of lines) {
      if (!line.trim()) continue;
      const data = JSON.parse(line);
      if (data.error) throw new Error(data.error);
      const piece = (data.message && data.message.content) || '';
      full += piece;
      process.stdout.write(piece);
    }
  }
  process.stdout.write('\n');
  return full;
}

// Bir kullanıcı istemini, model etiket kullanmayı bırakana kadar yürütür.
async function handle(prompt) {
  history.push({ role: 'user', content: prompt });
  for (let step = 0; step < MAX_STEPS; step++) {
    trimHistory();
    const reply = await chat();
    history.push({ role: 'assistant', content: reply });

    const results = runTools(reply);
    if (results.length === 0) return;

    for (const r of results) console.log(`\x1b[36m[ajan] ${r.split('\n')[0]}\x1b[0m`);
    history.push({ role: 'user', content: `SİSTEM NOTU - işlem sonuçları:\n${results.join('\n\n')}` });
  }
  console.log(`\x1b[33m[ajan] Azami tur sayısına (${MAX_STEPS}) ulaşıldı.\x1b[0m`);
}

async function main() {
  console.log(`QwenNodeAgent | model: ${MODEL} | dizin: ${ROOT}`);
  console.log('Çıkmak için "exit" yazın.\n');

  const rl = readline.createInterface({ input: process.stdin, output: process.stdout });
  rl.setPrompt('> ');
  rl.prompt();
  for await (const line of rl) {
    const prompt = line.trim();
    if (prompt === 'exit' || prompt === 'quit') break;
    if (prompt) {
      try {
        await handle(prompt);
      } catch (e) {
        const hint = e.cause && e.cause.code === 'ECONNREFUSED' ? ' (Ollama çalışmıyor: "ollama serve")' : '';
        console.error(`\x1b[31mHata: ${e.message}${hint}\x1b[0m`);
      }
    }
    rl.prompt();
  }
  rl.close();
}

if (require.main === module) main();

module.exports = { runTools, resolveSafe, stripFence };
