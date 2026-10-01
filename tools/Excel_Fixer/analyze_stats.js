const XLSX = require('xlsx');
const fs = require('fs');
const path = require('path');
const dir = 'E:/WebRecorder/tools/Excel_Fixer/fixed';
const files = fs.readdirSync(dir).filter(f => f.endsWith('.xlsx'));

let totalRows = 0;
let totalWithUrl = 0;
let totalWithoutUrl = 0;
let totalDuplicateUrls = 0;
const urlSet = new Set();
const titleSet = new Set();

console.log('--- SHEET BREAKDOWN ---');
for (const file of files) {
  const wb = XLSX.readFile(path.join(dir, file));
  const rows = XLSX.utils.sheet_to_json(wb.Sheets[wb.SheetNames[0]], { header: 1 });
  let sheetValid = 0;
  let sheetNoUrl = 0;
  for (let i = 1; i < rows.length; i++) {
    const title = (rows[i][0] || '').toString().trim();
    const url = (rows[i][1] || '').toString().trim();
    if (!title && !url) continue;
    totalRows++;
    if (url && (url.startsWith('http://') || url.startsWith('https://'))) {
      sheetValid++;
      totalWithUrl++;
      if (urlSet.has(url)) {
        totalDuplicateUrls++;
      } else {
        urlSet.add(url);
      }
    } else {
      sheetNoUrl++;
      totalWithoutUrl++;
    }
    titleSet.add(title.toLowerCase());
  }
  console.log(`${file}: ${rows.length - 1} movies (${sheetValid} valid URLs, ${sheetNoUrl} missing/invalid URLs)`);
}

console.log('\n--- TOTAL STATS ---');
console.log('Total movies across all 16 sheets:', totalRows);
console.log('Total movies with valid URLs:', totalWithUrl);
console.log('Total movies WITHOUT valid URLs (empty/unresolved):', totalWithoutUrl);
console.log('Total unique URLs:', urlSet.size);
console.log('Total duplicate URLs across sheets:', totalDuplicateUrls);
console.log('Total unique movie titles (case-insensitive):', titleSet.size);
