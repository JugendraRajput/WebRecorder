const XLSX = require('xlsx');
const fs = require('fs');
const path = require('path');

const wordList = [
  'sex', 'sexy', 'porn', 'porno', 'xxx', 'adult', 'nude', 'nudity', 
  'abuse', 'abusive', 'erotic', 'erotica', 'hentai', 'rape', 'incest'
];
const regex = new RegExp('\\b(' + wordList.join('|') + ')\\b', 'i');

const dir = 'E:/WebRecorder/tools/Excel_Fixer/fixed';
const files = fs.readdirSync(dir).filter(f => f.endsWith('.xlsx'));

console.log('Filtering unwanted movies from all Excel files...');
let totalFiltered = 0;

for (const file of files) {
  const filePath = path.join(dir, file);
  const wb = XLSX.readFile(filePath);
  const sheetName = wb.SheetNames[0];
  const ws = wb.Sheets[sheetName];
  const rows = XLSX.utils.sheet_to_json(ws, { header: 1 });

  const header = rows[0];
  const keptRows = [header];
  const removedTitles = [];

  for (let i = 1; i < rows.length; i++) {
    const row = rows[i];
    const title = (row[0] || '').toString().trim();
    if (!title) continue;

    // Check unwanted keywords (whole word only)
    if (regex.test(title)) {
      const matchWord = title.match(regex)[0];
      removedTitles.push(`"${title}" (matched: ${matchWord})`);
    } else {
      keptRows.push(row);
    }
  }

  if (removedTitles.length > 0) {
    console.log(`\n[${file}] Removed ${removedTitles.length} items:`);
    removedTitles.forEach(t => console.log('   - ' + t));
    totalFiltered += removedTitles.length;

    // Save updated sheet
    const newWs = XLSX.utils.aoa_to_sheet(keptRows);
    const newWb = XLSX.utils.book_new();
    XLSX.utils.book_append_sheet(newWb, newWs, sheetName);
    XLSX.writeFile(newWb, filePath);
  }
}

console.log(`\nFiltering complete. Total removed across all sheets: ${totalFiltered}`);
