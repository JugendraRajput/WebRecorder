const XLSX = require('xlsx');
const fs = require('fs');
const path = require('path');

const fixedDir = 'E:/WebRecorder/tools/Excel_Fixer/fixed';
const files = fs.readdirSync(fixedDir).filter(f => f.endsWith('.xlsx'));

const wordMap = new Map();

for (const file of files) {
  const wb = XLSX.readFile(path.join(fixedDir, file));
  const rows = XLSX.utils.sheet_to_json(wb.Sheets[wb.SheetNames[0]], { header: 1 });
  for (let i = 1; i < rows.length; i++) {
    const title = (rows[i][0] || '').toString();
    const words = title.toLowerCase().replace(/[^a-z0-9]/g, ' ').split(/\s+/).filter(Boolean);
    for (const w of words) {
      if (!wordMap.has(w)) wordMap.set(w, []);
      wordMap.get(w).push({ file, title, row: i });
    }
  }
}

// Let's check any words that match potential sensitive prefixes or stems
const patterns = [
  /^sex/, /^porn/, /^xxx/, /^erot/, /^nud/, /^nak/, /^lust/, /^bitch/, /^bastard/,
  /^ass/, /^butt/, /^fuck/, /^shit/, /^cunt/, /^dick/, /^cock/, /^puss/, /^tit/,
  /^boob/, /^whore/, /^slut/, /^pimp/, /^strip/, /^orgy/, /^orgasm/, /^kink/,
  /^fetish/, /^vagina/, /^penis/, /^dildo/, /^rape/, /^rapist/, /^incest/, /^abuse/,
  /^abusive/, /^molest/, /^pedop/, /^paedop/, /^hooker/, /^brothel/, /^prostitut/,
  /^vulgar/, /^swear/, /^curse/, /^damn/, /^hell/, /^kill/, /^murder/, /^blood/,
  /^nazi/, /^hitler/, /^fag/, /^nigg/, /^retard/, /^dumb/, /^jackass/, /^sin/, /^evil/
];

console.log('--- POTENTIAL SENSITIVE WORDS FOUND IN TITLES ---');
const matchedWords = [];
for (const [word, occurrences] of wordMap.entries()) {
  for (const pat of patterns) {
    if (pat.test(word)) {
      matchedWords.push({ word, count: occurrences.length, occurrences });
      break;
    }
  }
}

matchedWords.sort((a, b) => a.word.localeCompare(b.word));
for (const m of matchedWords) {
  console.log(`Word: "${m.word}" (Count: ${m.count})`);
  for (const o of m.occurrences) {
    console.log(`   [${o.file}] Row ${o.row}: "${o.title}"`);
  }
}
