const XLSX = require('xlsx');
const fs = require('fs');
const path = require('path');

const fixedDir = 'E:/WebRecorder/tools/Excel_Fixer/fixed';
const files = fs.readdirSync(fixedDir).filter(f => f.endsWith('.xlsx'));

// Clear explicit/abusive words
const coreSexAndAbuse = [
  // Sex & Adult
  'sex', 'sexy', 'sexual', 'sexuality', 'porn', 'porno', 'pornography', 'pornographic',
  'xxx', 'nude', 'nudity', 'naked', 'erotic', 'erotica', 'hentai', 'rape', 'rapist',
  'incest', 'orgasm', 'intercourse', 'masturbate', 'masturbation', 'dildo',
  'vagina', 'penis', 'blowjob', 'handjob', 'cum', 'stripper', 'strippers', 'striptease',
  'escort', 'prostitute', 'prostitution', 'brothel', 'whore', 'whores',
  'slut', 'sluts', 'tits', 'titties', 'boobs', 'topless', 'bdsm', 'fetish',
  'threesome', 'orgy', 'pedophile', 'paedophile', 'molest', 'molestation',
  'molester', 'adult', 'lust', 'pimp', 'pimpin', 'pimping',
  // Abusive & Profanity & Slurs
  'fuck', 'fucker', 'fucking', 'fucked', 'motherfucker', 'motherfucking',
  'shit', 'bullshit', 'shitty', 'dipshit', 'bitch', 'bitches', 'bitching',
  'bastard', 'bastards', 'asshole', 'assholes', 'dumbass', 'jackass',
  'cunt', 'cunts', 'dickhead', 'cocksucker', 'nigger', 'nigga',
  'faggot', 'fag', 'retard', 'retarded', 'abuse', 'abusive', 'abuser', 'vulgar',
  // Hindi abusive
  'chutiya', 'madarchod', 'bhenchod', 'gaand', 'bhosadi', 'randi', 'harami', 'kameena', 'kamina'
];

// Additional slang/borderline words
const borderlineWords = [
  'ass', 'kinky'
];

const coreRegex = new RegExp('\\b(' + coreSexAndAbuse.join('|') + ')\\b', 'i');
const borderlineRegex = new RegExp('\\b(' + borderlineWords.join('|') + ')\\b', 'i');

console.log('--- CORE SEX & ABUSIVE WORDS ---');
for (const file of files) {
  const wb = XLSX.readFile(path.join(fixedDir, file));
  const rows = XLSX.utils.sheet_to_json(wb.Sheets[wb.SheetNames[0]], { header: 1 });
  for (let i = 1; i < rows.length; i++) {
    const title = (rows[i][0] || '').toString().trim();
    const m = title.match(coreRegex);
    if (m) {
      console.log(`[${file}] Row ${i}: "${title}" -> [${m[0]}]`);
    }
  }
}

console.log('\n--- BORDERLINE (ASS / KINKY) ---');
for (const file of files) {
  const wb = XLSX.readFile(path.join(fixedDir, file));
  const rows = XLSX.utils.sheet_to_json(wb.Sheets[wb.SheetNames[0]], { header: 1 });
  for (let i = 1; i < rows.length; i++) {
    const title = (rows[i][0] || '').toString().trim();
    const m = title.match(borderlineRegex);
    if (m && !title.match(coreRegex)) {
      console.log(`[${file}] Row ${i}: "${title}" -> [${m[0]}]`);
    }
  }
}
