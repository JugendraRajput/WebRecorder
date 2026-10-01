const XLSX = require('xlsx');
const fs = require('fs');
const path = require('path');
const fixedDir = 'E:/WebRecorder/tools/Excel_Fixer/fixed';
const files = fs.readdirSync(fixedDir).filter(f => f.endsWith('.xlsx'));

const checkWords = [
  'ass', 'badass', 'jackass', 'dumbass', 'booty', 'nasty', 'dirty',
  'slut', 'sluts', 'whore', 'whores', 'bitch', 'bitches', 'bastard', 'bastards',
  'cunt', 'cunts', 'cock', 'cocks', 'dick', 'dicks', 'pussy', 'pussies',
  'penis', 'vagina', 'dildo', 'orgasm', 'orgy', 'erotic', 'erotica',
  'nude', 'nudity', 'naked', 'stripper', 'strippers', 'strip',
  'pimp', 'pimpin', 'porno', 'porn', 'xxx', 'sex', 'sexy', 'sexual',
  'molest', 'rape', 'rapist', 'incest', 'abuse', 'abusive', 'vulgar', 'lust'
];

const regex = new RegExp('\\b(' + checkWords.join('|') + ')\\b', 'i');

for (const file of files) {
  const wb = XLSX.readFile(path.join(fixedDir, file));
  const rows = XLSX.utils.sheet_to_json(wb.Sheets[wb.SheetNames[0]], { header: 1 });
  for (let i = 1; i < rows.length; i++) {
    const title = (rows[i][0] || '').toString().trim();
    const m = title.match(regex);
    if (m) {
      console.log(`[${file}] Row ${i}: "${title}" (matched: [${m[0]}])`);
    }
  }
}
