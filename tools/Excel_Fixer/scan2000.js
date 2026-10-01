const XLSX = require('xlsx');

async function getSummary(title) {
  try {
    const cleanTitle = encodeURIComponent(title.replace(/ /g, '_'));
    const url = `https://en.wikipedia.org/api/rest_v1/page/summary/${cleanTitle}`;
    const res = await fetch(url, {
      headers: { 'User-Agent': 'WebRecorderMovieFixer/1.0 (contact@jdworks.in)' }
    });
    if (!res.ok) return null;
    return await res.json();
  } catch (e) {
    return null;
  }
}

function extractWikiTitle(url) {
  if (!url || typeof url !== 'string') return null;
  const m = url.match(/\/wiki\/([^#?]+)/);
  if (!m) return null;
  try {
    return decodeURIComponent(m[1]).replace(/_/g, ' ');
  } catch (e) {
    return m[1].replace(/_/g, ' ');
  }
}

function isLikelyFilm(summary, targetYear) {
  if (!summary) return false;
  if (summary.type === 'disambiguation') return false;
  
  const desc = (summary.description || '').toLowerCase();
  const extract = (summary.extract || '').toLowerCase();
  const text = `${desc} ${extract}`;

  // Positive signs
  const hasFilmKeyword = text.includes('film') || text.includes('movie') || text.includes('directed by');
  
  // Negative signs if NO clear film context or explicitly other medium
  const nonFilmWords = ['novel', 'album', 'song', 'comic book', 'video game', 'television series', 'tv series', 'soundtrack'];
  const hasNegativeOnly = nonFilmWords.some(w => desc.includes(w)) && !desc.includes('film') && !desc.includes('movie');

  if (hasNegativeOnly) return false;

  return hasFilmKeyword;
}

async function scan2000() {
  const wb = XLSX.readFile('E:/WebRecorder/tools/Excel_Fixer/raw/Movie_List_2000.xlsx');
  const ws = wb.Sheets[wb.SheetNames[0]];
  const rows = XLSX.utils.sheet_to_json(ws, { header: 1 });
  console.log(`Scanning Movie_List_2000 (${rows.length - 1} movies)...`);

  let countBad = 0;
  for (let i = 1; i < rows.length; i++) {
    const [title, url] = rows[i];
    if (!title || !url) continue;

    const wikiTitle = extractWikiTitle(url);
    if (!wikiTitle) {
      console.log(`[Row ${i + 1}] INVALID URL FORMAT: "${title}" -> ${url}`);
      countBad++;
      continue;
    }

    const summary = await getSummary(wikiTitle);
    const valid = isLikelyFilm(summary, 2000);

    if (!valid) {
      countBad++;
      const type = summary ? summary.type : 'NOT FOUND (404)';
      const desc = summary?.description || '(no desc)';
      console.log(`[Row ${i + 1}] NEEDS FIX: "${title}" | type=${type} | desc="${desc}" | url=${url}`);
    }
  }
  console.log(`\nScan complete. Total entries: ${rows.length - 1}, Need fix: ${countBad}`);
}

scan2000();
