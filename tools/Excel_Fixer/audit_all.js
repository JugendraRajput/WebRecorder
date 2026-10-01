const XLSX = require('xlsx');
const fs = require('fs');
const path = require('path');

const fixedDir = 'E:/WebRecorder/tools/Excel_Fixer/fixed';
const files = fs.readdirSync(fixedDir).filter(f => f.endsWith('.xlsx'));

// We test every word
const patterns = [
  { label: 'SEX_EROTIC_ADULT', regex: /\b(sex|sexy|sexual|sexuality|porn|porno|pornography|pornographic|xxx|adult|nude|nudity|naked|erotic|erotica|hentai|rape|rapist|incest|orgasm|orgy|threesome|dildo|vagina|penis|stripper|strippers|striptease|whore|whores|slut|sluts|tits|titties|boobs|topless|bdsm|fetish|kinky|kink|lust|pimp|pimpin|pimping)\b/i },
  { label: 'ABUSIVE_PROFANITY', regex: /\b(fuck|fucker|fucking|fucked|motherfucker|motherfucking|shit|bullshit|shitty|dipshit|bitch|bitches|bitching|bastard|bastards|ass|asshole|assholes|dumbass|jackass|cunt|cunts|dickhead|cocksucker|nigger|nigga|faggot|fag|retard|retarded|abuse|abusive|abuser|vulgar|chutiya|madarchod|bhenchod|gaand|bhosadi|randi|harami|kameena|kamina)\b/i }
];

console.log('=== FULL AUDIT OF ALL 16 FILES ===');
const matched = [];

for (const file of files) {
  const wb = XLSX.readFile(path.join(fixedDir, file));
  const rows = XLSX.utils.sheet_to_json(wb.Sheets[wb.SheetNames[0]], { header: 1 });
  for (let i = 1; i < rows.length; i++) {
    const title = (rows[i][0] || '').toString().trim();
    if (!title) continue;

    for (const p of patterns) {
      const m = title.match(p.regex);
      if (m) {
        matched.push({ file, row: i, title, type: p.label, match: m[0] });
        console.log(`[${file}] [${p.label}] Row ${i}: "${title}" (matched: ${m[0]})`);
        break;
      }
    }
  }
}

console.log(`\nTotal matches across all 16 files: ${matched.length}`);
