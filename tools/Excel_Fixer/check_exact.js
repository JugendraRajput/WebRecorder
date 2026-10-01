const XLSX = require('xlsx');
const fs = require('fs');
const path = require('path');

const fixedDir = 'E:/WebRecorder/tools/Excel_Fixer/fixed';
const files = fs.readdirSync(fixedDir).filter(f => f.endsWith('.xlsx'));

// Comprehensive explicit/sexual and abusive/profane words (exact words, not substrings)
const targetWords = [
  // Sex & Adult & Erotic
  'sex', 'sexy', 'sexual', 'sexuality', 'porn', 'porno', 'pornography', 'pornographic',
  'xxx', 'nude', 'nudity', 'naked', 'erotic', 'erotica', 'hentai', 'rape', 'rapist',
  'incest', 'orgasm', 'intercourse', 'masturbate', 'masturbation', 'dildo',
  'vagina', 'penis', 'blowjob', 'handjob', 'cum', 'stripper', 'strippers',
  'escort', 'prostitute', 'prostitution', 'brothel', 'whore', 'whores',
  'slut', 'sluts', 'tits', 'titties', 'boobs', 'topless', 'bdsm', 'fetish',
  'threesome', 'orgy', 'pedophile', 'paedophile', 'molest', 'molestation',
  'molester', 'adult', 'lust', 'erotism', 'pimp', 'pimpin', 'vulgar',
  // Abusive & Profanity & Slurs
  'fuck', 'fucker', 'fucking', 'fucked', 'motherfucker', 'motherfucking',
  'shit', 'bullshit', 'shitty', 'bitch', 'bitches', 'bitching',
  'bastard', 'bastards', 'asshole', 'assholes', 'dumbass', 'jackass',
  'cunt', 'cunts', 'dickhead', 'dipshit', 'cocksucker', 'nigger', 'nigga',
  'faggot', 'fag', 'retard', 'retarded', 'abuse', 'abusive', 'abuser',
  // Hindi abusive
  'chutiya', 'madarchod', 'bhenchod', 'gaand', 'bhosadi', 'randi', 'harami', 'kameena'
];

const regex = new RegExp('\\b(' + targetWords.join('|') + ')\\b', 'i');

console.log('--- EXACT TARGET MATCHES IN CURRENT FIXED FILES ---');
let count = 0;
for (const file of files) {
  const wb = XLSX.readFile(path.join(fixedDir, file));
  const rows = XLSX.utils.sheet_to_json(wb.Sheets[wb.SheetNames[0]], { header: 1 });
  for (let i = 1; i < rows.length; i++) {
    const title = (rows[i][0] || '').toString().trim();
    if (!title) continue;
    const m = title.match(regex);
    if (m) {
      console.log(`[${file}] Row ${i}: "${title}" (matched: [${m[0]}])`);
      count++;
    }
  }
}
console.log(`Total exact matches: ${count}`);
