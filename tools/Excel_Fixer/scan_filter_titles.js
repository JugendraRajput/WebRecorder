const XLSX = require('xlsx');
const fs = require('fs');
const path = require('path');

const backupDir = 'E:/WebRecorder/tools/Excel_Fixer/backup';
const fixedDir = 'E:/WebRecorder/tools/Excel_Fixer/fixed';
const files = fs.readdirSync(backupDir).filter(f => f.endsWith('.xlsx'));

// Word categories for sex, abusive, and related words
const wordList = [
  // Sex & Erotic & Nudity
  'sex', 'sexy', 'sexual', 'sexuality', 'porn', 'porno', 'pornography', 'pornographic',
  'xxx', 'nude', 'nudity', 'naked', 'erotic', 'erotica', 'hentai', 'rape', 'rapist',
  'incest', 'orgasm', 'intercourse', 'masturbate', 'masturbation', 'dildo',
  'vagina', 'penis', 'blowjob', 'handjob', 'cum', 'stripper', 'strippers',
  'escort', 'prostitute', 'prostitution', 'brothel', 'whore', 'whores',
  'slut', 'sluts', 'tits', 'titties', 'boobs', 'topless', 'bdsm', 'fetish',
  'threesome', 'orgy', 'pedophile', 'paedophile', 'molest', 'molestation',
  'molester', 'adult', 'lust', 'erotism',
  // Abusive & Profanity & Vulgarity
  'fuck', 'fucker', 'fucking', 'fucked', 'motherfucker', 'motherfucking',
  'shit', 'bullshit', 'shitty', 'bitch', 'bitches', 'bitching',
  'bastard', 'bastards', 'asshole', 'assholes', 'dumbass',
  'cunt', 'cunts', 'dickhead', 'dipshit', 'cocksucker', 'nigger', 'nigga',
  'faggot', 'fag', 'retard', 'retarded', 'abuse', 'abusive', 'abuser',
  // Hindi / Indian abusive words if any
  'chutiya', 'madarchod', 'bhenchod', 'gaand', 'bhosadi', 'randi', 'harami', 'kameena'
];

const regex = new RegExp('\\b(' + wordList.join('|') + ')\\b', 'i');

console.log('=== CHECKING ALL TITLES IN RAW BACKUP ===');
let backupMatches = 0;
for (const file of files) {
  const filePath = path.join(backupDir, file);
  const wb = XLSX.readFile(filePath);
  const rows = XLSX.utils.sheet_to_json(wb.Sheets[wb.SheetNames[0]], { header: 1 });
  for (let i = 1; i < rows.length; i++) {
    const title = (rows[i][0] || '').toString().trim();
    if (!title) continue;
    const m = title.match(regex);
    if (m) {
      console.log(`[Backup ${file}] Row ${i}: "${title}" -> [${m[0]}]`);
      backupMatches++;
    }
  }
}
console.log(`Total in backup matching: ${backupMatches}\n`);

console.log('=== CHECKING ALL TITLES CURRENTLY IN FIXED ===');
let fixedMatches = 0;
for (const file of files) {
  const filePath = path.join(fixedDir, file);
  if (!fs.existsSync(filePath)) continue;
  const wb = XLSX.readFile(filePath);
  const rows = XLSX.utils.sheet_to_json(wb.Sheets[wb.SheetNames[0]], { header: 1 });
  for (let i = 1; i < rows.length; i++) {
    const title = (rows[i][0] || '').toString().trim();
    if (!title) continue;
    const m = title.match(regex);
    if (m) {
      console.log(`[Fixed ${file}] Row ${i}: "${title}" -> [${m[0]}]`);
      fixedMatches++;
    }
  }
}
console.log(`Total in fixed matching: ${fixedMatches}\n`);
