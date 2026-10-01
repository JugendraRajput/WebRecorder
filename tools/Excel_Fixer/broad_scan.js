const XLSX = require('xlsx');
const fs = require('fs');
const path = require('path');

const dir = 'E:/WebRecorder/tools/Excel_Fixer/fixed';
const files = fs.readdirSync(dir).filter(f => f.endsWith('.xlsx'));

// Broad search for sexual/vulgar/abusive roots
const roots = [
  'sex', 'sexy', 'sexual', 'sexuality', 'porn', 'porno', 'xxx', 'erot', 'nude', 'nudit', 'naked',
  'hooker', 'prostitut', 'whore', 'slut', 'pimp', 'brothel',
  'escort', 'stripper', 'strip', 'orgy', 'threesome', 'bdsm', 'kink', 'fetish',
  'milf', 'dildo', 'vagina', 'penis', 'cock', 'dick', 'pussy', 'boob', 'tits',
  'asshole', 'fuck', 'shit', 'bitch', 'bastard', 'cunt', 'prick', 'twat',
  'wanker', 'motherfuck', 'jackass', 'bullshit', 'abous', 'abuse', 'abusive',
  'rape', 'rapist', 'incest', 'pedophil', 'paedophil', 'molest', 'lewd', 'adult',
  'chutiya', 'madarchod', 'bhenchod', 'gaand', 'bhosadi', 'randi', 'harami', 'kameena', 'kamina',
  'slut', 'slutty', 'horny'
];

const regex = new RegExp('\\b(' + roots.join('|') + ')', 'i');

console.log('--- BROAD SCAN RESULTS ---');
for (const file of files) {
  const filePath = path.join(dir, file);
  const wb = XLSX.readFile(filePath);
  const rows = XLSX.utils.sheet_to_json(wb.Sheets[wb.SheetNames[0]], { header: 1 });
  for (let i = 1; i < rows.length; i++) {
    const title = (rows[i][0] || '').toString().trim();
    if (!title) continue;
    const m = title.match(regex);
    if (m) {
      console.log(`[${file}] "${title}" (matched: ${m[0]})`);
    }
  }
}
