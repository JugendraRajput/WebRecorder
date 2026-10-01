const XLSX = require('xlsx');
const fs = require('fs');
const path = require('path');

const fixedDir = 'E:/WebRecorder/tools/Excel_Fixer/fixed';
const files = fs.readdirSync(fixedDir).filter(f => f.endsWith('.xlsx'));

// Comprehensive explicit/sexual and abusive/profane words and related stems
const targetWords = [
  // Sex, Erotica, Adult & Anatomy
  'sex', 'sexy', 'sexual', 'sexuality', 'porn', 'porno', 'pornography', 'pornographic',
  'xxx', 'nude', 'nudity', 'naked', 'erotic', 'erotica', 'hentai', 'rape', 'rapist',
  'incest', 'orgasm', 'intercourse', 'masturbate', 'masturbation', 'dildo',
  'vagina', 'penis', 'blowjob', 'handjob', 'cum', 'stripper', 'strippers', 'striptease',
  'escort', 'prostitute', 'prostitution', 'brothel', 'whore', 'whores',
  'slut', 'sluts', 'tits', 'titties', 'boobs', 'topless', 'bdsm', 'fetish',
  'threesome', 'orgy', 'pedophile', 'paedophile', 'molest', 'molestation',
  'molester', 'adult', 'lust', 'pimp', 'pimpin', 'pimping',
  // Abusive, Profanity & Vulgarity
  'fuck', 'fucker', 'fucking', 'fucked', 'motherfucker', 'motherfucking',
  'shit', 'bullshit', 'shitty', 'dipshit', 'bitch', 'bitches', 'bitching',
  'bastard', 'bastards', 'asshole', 'assholes', 'dumbass', 'jackass',
  'cunt', 'cunts', 'dickhead', 'cocksucker', 'nigger', 'nigga',
  'faggot', 'fag', 'retard', 'retarded', 'abuse', 'abusive', 'abuser', 'vulgar',
  // Hindi / regional abusive
  'chutiya', 'madarchod', 'bhenchod', 'gaand', 'bhosadi', 'randi', 'harami', 'kameena', 'kamina'
];

// Word boundary regex
const regex = new RegExp('\\b(' + targetWords.join('|') + ')\\b', 'i');

console.log('=== DRY RUN: SCANNING CURRENT FIXED FILES ===');
let matchCount = 0;
const toRemove = [];

for (const file of files) {
  const filePath = path.join(fixedDir, file);
  const wb = XLSX.readFile(filePath);
  const sheetName = wb.SheetNames[0];
  const ws = wb.Sheets[sheetName];
  const rows = XLSX.utils.sheet_to_json(ws, { header: 1 });

  for (let i = 1; i < rows.length; i++) {
    const row = rows[i];
    const title = (row[0] || '').toString().trim();
    const url = (row[1] || '').toString().trim();
    if (!title) continue;

    const m = title.match(regex);
    if (m) {
      matchCount++;
      toRemove.push({ file, row: i, title, matchedWord: m[0], url });
      console.log(`[${file}] Row ${i}: "${title}" (matched: [${m[0]}])`);
    }
  }
}

console.log(`\nTotal matches to remove: ${matchCount}`);
