const XLSX = require('xlsx');
const rawWb = XLSX.readFile('E:/WebRecorder/tools/Excel_Fixer/raw/Movie_List_2001.xlsx');
const rawRows = XLSX.utils.sheet_to_json(rawWb.Sheets[rawWb.SheetNames[0]], { header: 1 });
const fixedWb = XLSX.readFile('E:/WebRecorder/tools/Excel_Fixer/fixed/Movie_List_2001.xlsx');
const fixedRows = XLSX.utils.sheet_to_json(fixedWb.Sheets[fixedWb.SheetNames[0]], { header: 1 });

console.log('Unresolved in Movie_List_2001.xlsx:');
for (let i = 1; i < rawRows.length; i++) {
  const [rawTitle, rawUrl] = rawRows[i] || [];
  const [fixedTitle, fixedUrl] = fixedRows[i] || [];
  if (rawUrl === fixedUrl) {
    console.log(`[Row ${i+1}] "${rawTitle}" | URL: ${rawUrl}`);
  }
}
