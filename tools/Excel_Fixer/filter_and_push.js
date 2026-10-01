const XLSX = require('xlsx');
const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');

const adbPath = 'C:\\Users\\jugen\\AppData\\Local\\Android\\Sdk\\platform-tools\\adb.exe';
const fixedDir = 'E:/WebRecorder/tools/Excel_Fixer/fixed';
const backupDir = 'E:/WebRecorder/tools/Excel_Fixer/backup_pre_filter_sex_abuse';

// 1. Create safety backup
if (!fs.existsSync(backupDir)) {
  fs.mkdirSync(backupDir, { recursive: true });
}

const files = fs.readdirSync(fixedDir).filter(f => f.endsWith('.xlsx'));
console.log(`Backing up ${files.length} fixed Excel files to ${backupDir}...`);
for (const file of files) {
  fs.copyFileSync(path.join(fixedDir, file), path.join(backupDir, file));
}
console.log('Backup complete!\n');

// 2. Comprehensive word filter
const sexAndAbusiveWords = [
  // Sex, adult, erotic, pornography, anatomy, and related words
  'sex', 'sexy', 'sexual', 'sexuality', 'porn', 'porno', 'pornography', 'pornographic',
  'xxx', 'adult', 'nude', 'nudity', 'naked', 'erotic', 'erotica', 'hentai',
  'rape', 'rapist', 'rapists', 'incest', 'orgasm', 'orgasms', 'intercourse',
  'masturbate', 'masturbation', 'dildo', 'dildos', 'vagina', 'penis',
  'blowjob', 'blowjobs', 'handjob', 'handjobs', 'cum', 'cumming',
  'stripper', 'strippers', 'striptease',
  'escort', 'escorts', 'prostitute', 'prostitutes', 'prostitution', 'brothel',
  'whore', 'whores', 'slut', 'sluts', 'slutty',
  'tits', 'titties', 'boobs', 'topless', 'bdsm', 'fetish', 'fetishes',
  'threesome', 'threesomes', 'orgy', 'orgies',
  'pedophile', 'pedophiles', 'paedophile', 'paedophiles',
  'molest', 'molested', 'molestation', 'molester',
  'lust', 'pimp', 'pimps', 'pimpin', 'pimping',
  'kinky',

  // Abusive, vulgarity, curses, slurs, profanity
  'fuck', 'fucker', 'fuckers', 'fucking', 'fucked', 'motherfucker', 'motherfuckers', 'motherfucking',
  'shit', 'shits', 'bullshit', 'shitty', 'dipshit',
  'bitch', 'bitches', 'bitching',
  'bastard', 'bastards',
  'ass', 'asshole', 'assholes', 'dumbass', 'jackass',
  'cunt', 'cunts',
  'dickhead', 'dickheads', 'cocksucker', 'cocksuckers',
  'nigger', 'niggers', 'nigga', 'niggas',
  'faggot', 'faggots', 'fag', 'fags',
  'retard', 'retards', 'retarded',
  'abuse', 'abusive', 'abuser',
  'vulgar',

  // Hindi / Urdu / South Asian abusive/vulgar words
  'chutiya', 'chutiyo', 'madarchod', 'mc', 'bhenchod', 'bc', 'gaand', 'gand',
  'bhosadi', 'bhosadike', 'randi', 'harami', 'kameena', 'kamina', 'saala', 'kamini'
];

const regex = new RegExp('\\b(' + sexAndAbusiveWords.join('|') + ')\\b', 'i');

console.log('=== FILTERING 16 EXCEL FILES FOR SEX AND ABUSIVE WORDS ===\n');
let grandTotalRemoved = 0;
const report = [];

for (const file of files) {
  const filePath = path.join(fixedDir, file);
  const wb = XLSX.readFile(filePath);
  const sheetName = wb.SheetNames[0];
  const ws = wb.Sheets[sheetName];
  const rows = XLSX.utils.sheet_to_json(ws, { header: 1 });

  const header = rows[0];
  const keptRows = [header];
  const removedInFile = [];

  for (let i = 1; i < rows.length; i++) {
    const row = rows[i];
    const title = (row[0] || '').toString().trim();
    if (!title) continue;

    const m = title.match(regex);
    if (m) {
      removedInFile.push({ title, matchedWord: m[0], originalRow: i });
    } else {
      keptRows.push(row);
    }
  }

  if (removedInFile.length > 0) {
    grandTotalRemoved += removedInFile.length;
    console.log(`[${file}] ${rows.length - 1} -> ${keptRows.length - 1} movies (Removed ${removedInFile.length}):`);
    removedInFile.forEach(r => {
      console.log(`   - "${r.title}" (matched: [${r.matchedWord}])`);
      report.push({ file, title: r.title, keyword: r.matchedWord });
    });

    // Write updated sheet
    const newWs = XLSX.utils.aoa_to_sheet(keptRows);
    const newWb = XLSX.utils.book_new();
    XLSX.utils.book_append_sheet(newWb, newWs, sheetName);
    XLSX.writeFile(newWb, filePath);
  } else {
    console.log(`[${file}] 0 removed (${rows.length - 1} clean movies)`);
  }
}

console.log(`\n========================================`);
console.log(`Total removed across all 16 files: ${grandTotalRemoved}`);
console.log(`========================================\n`);

// Save audit log
fs.writeFileSync('E:/WebRecorder/tools/Excel_Fixer/filter_report.json', JSON.stringify(report, null, 2));

// 3. Push to mobile device
console.log('Pushing updated Excel files to mobile device /sdcard/Movies/Excel/ ...');
try {
  for (const file of files) {
    const localFile = path.join(fixedDir, file);
    const pushCmd = `"${adbPath}" push "${localFile}" /sdcard/Movies/Excel/`;
    execSync(pushCmd, { stdio: 'inherit' });
  }
  console.log('\nAll 16 Excel files successfully pushed to mobile device!');
} catch (e) {
  console.error('ADB push error:', e.message);
  process.exit(1);
}

// 4. Verify on mobile device
console.log('\nVerifying files on mobile device:');
try {
  const verifyCmd = `"${adbPath}" shell "ls -l /sdcard/Movies/Excel/"`;
  const output = execSync(verifyCmd, { encoding: 'utf8' });
  console.log(output);
} catch (e) {
  console.error('ADB verify error:', e.message);
}
