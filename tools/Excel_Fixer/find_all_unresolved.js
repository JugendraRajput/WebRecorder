const fs = require('fs');

const log = fs.readFileSync('C:/Users/jugen/.gemini/antigravity/brain/be7109a6-22d0-4d93-990e-d6b2dac83712/.system_generated/tasks/task-196.log', 'utf8');
const lines = log.split('\n');

const failed = [];
for (const line of lines) {
  const m = line.match(/Resolving:\s*"([^"]+)"\s*\((\d+)\)\.\.\.\s*FAILED!/);
  if (m) {
    failed.push({ title: m[1], year: parseInt(m[2], 10) });
  }
}

console.log(`Total failed entries in task-196: ${failed.length}`);
console.log(JSON.stringify(failed, null, 2));
