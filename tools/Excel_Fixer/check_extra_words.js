const XLSX = require('xlsx');
const fs = require('fs');
const path = require('path');

const fixedDir = 'E:/WebRecorder/tools/Excel_Fixer/fixed';
const files = fs.readdirSync(fixedDir).filter(f => f.endsWith('.xlsx'));

const extraWords = [
  'butt', 'booty', 'cock', 'cocks', 'pussy', 'pussies', 'dick', 'dicks',
  'whore', 'whores', 'slut', 'sluts', 'cunt', 'cunts', 'prick', 'pricks',
  'twat', 'wanker', 'jackass', 'dipshit', 'tit', 'tits', 'titties', 'boob', 'boobs',
  'hooker', 'hookers', 'brothel', 'escort', 'escorts', 'prostitute', 'prostitutes',
  'molest', 'molester', 'molestation', 'rape', 'rapist', 'raping', 'incest',
  'fetish', 'fetishes', 'bdsm', 'threesome', 'threesomes', 'swinger', 'swingers'
];

const regex = new RegExp('\\b(' + extraWords.join('|') + ')\\b', 'i');

console.log('=== CHECKING EXTRA SLANG / SENSITIVE WORDS ===');
for (const file of files) {
  const wb = XLSX.readFile(path.join(fixedDir, file));
  const rows = XLSX.utils.sheet_to_json(wb.Sheets[wb.SheetNames[0]], { header: 1 });
  for (let i = 1; i < rows.length; i++) {
    const title = (rows[i][0] || '').toString().trim();
    if (!title) continue;
    const m = title.match(regex);
    if (m) {
      console.log(`[${file}] Row ${i}: "${title}" -> [${m[0]}]`);
    }
  }
}
