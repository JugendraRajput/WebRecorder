const XLSX = require('xlsx');
const fs = require('fs');
const path = require('path');

['fixed', 'backup', 'raw'].forEach(dirName => {
  const dir = 'E:/WebRecorder/tools/Excel_Fixer/' + dirName;
  if (!fs.existsSync(dir)) return;
  fs.readdirSync(dir).filter(f => f.endsWith('.xlsx')).forEach(f => {
    const wb = XLSX.readFile(path.join(dir, f));
    const rows = XLSX.utils.sheet_to_json(wb.Sheets[wb.SheetNames[0]], { header: 1 });
    rows.slice(1).forEach((r, idx) => {
      const title = (r[0] || '').toString();
      if (/sex/i.test(title)) {
        console.log(`[${dirName}/${f}] Row ${idx + 1}: ${title}`);
      }
    });
  });
});
