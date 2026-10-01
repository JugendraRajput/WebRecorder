const XLSX = require('xlsx');
const fs = require('fs');
const path = require('path');

const dir = 'E:/WebRecorder/tools/Excel_Fixer/raw';
const files = fs.readdirSync(dir).filter(f => f.endsWith('.xlsx'));

for (const f of files) {
  const wb = XLSX.readFile(path.join(dir, f));
  const ws = wb.Sheets[wb.SheetNames[0]];
  const rows = XLSX.utils.sheet_to_json(ws, { header: 1 });
  for (let i = 1; i < rows.length; i++) {
    const [title, url] = rows[i] || [];
    if (!title) continue;
    // Check if title has weird camelCase concatenation like "WordWord" with 2 capital letters adjacent, or no space before Capital
    if (/[a-z0-9][A-Z][a-z]/.test(title) && !title.includes('Mc') && !title.includes('Mac') && !title.includes('iPhone') && !title.includes('De') && !title.includes('Van')) {
      // Could be merged
      // console.log(`${f} Row ${i+1}: "${title}"`);
    }
    if (!url || typeof url !== 'string' || !url.startsWith('http')) {
      console.log(`${f} Row ${i+1}: NO VALID URL: "${title}" -> ${url}`);
    }
  }
}
console.log('URL format scan done.');
