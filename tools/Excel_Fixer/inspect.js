const XLSX = require('xlsx');
const path = require('path');
const fs = require('fs');

function inspectAll() {
  const dir = 'E:/WebRecorder/tools/Excel_Fixer/raw';
  const files = fs.readdirSync(dir).filter(f => f.endsWith('.xlsx'));
  for (const f of files) {
    const wb = XLSX.readFile(path.join(dir, f));
    const firstSheetName = wb.SheetNames[0];
    const ws = wb.Sheets[firstSheetName];
    const data = XLSX.utils.sheet_to_json(ws, { header: 1 });
    console.log(`${f} | Sheets: [${wb.SheetNames.join(', ')}] | Rows: ${data.length}`);
    if (data.length > 0) {
      console.log(`   Header:`, data[0]);
    }
    if (data.length > 1) {
      console.log(`   Row 1 :`, data[1]);
    }
  }
}

inspectAll();
