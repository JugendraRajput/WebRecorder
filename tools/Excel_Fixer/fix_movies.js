const XLSX = require('xlsx');
const fs = require('fs');
const path = require('path');

const USER_AGENT = 'WebRecorderMovieFixer/2.0 (https://webrecorder.jdworks.in; dev@jdworks.in)';

// In-memory cache for Wikipedia summaries and searches
const summaryCache = new Map();
const searchCache = new Map();

// Helper delay
const sleep = (ms) => new Promise(resolve => setTimeout(resolve, ms));

async function fetchWithRetry(url, options = {}, retries = 3) {
  for (let attempt = 0; attempt <= retries; attempt++) {
    try {
      const res = await fetch(url, options);
      if (res.status === 429) {
        const retryAfter = Math.max(3, parseInt(res.headers.get('Retry-After') || '3', 10));
        console.log(`\n    [Rate limited (429)] Waiting ${retryAfter}s before retry...`);
        await sleep(retryAfter * 1000);
        continue;
      }
      if (res.status >= 500 && attempt < retries) {
        await sleep(1500 * (attempt + 1));
        continue;
      }
      return res;
    } catch (e) {
      if (attempt === retries) return null;
      await sleep(1500 * (attempt + 1));
    }
  }
  return null;
}

async function getSummary(rawTitle) {
  if (!rawTitle) return null;
  const cleanTitle = rawTitle.trim().replace(/ /g, '_');
  if (summaryCache.has(cleanTitle)) {
    return summaryCache.get(cleanTitle);
  }

  try {
    const encoded = encodeURIComponent(cleanTitle);
    const url = `https://en.wikipedia.org/api/rest_v1/page/summary/${encoded}`;
    const res = await fetchWithRetry(url, {
      headers: { 'User-Agent': USER_AGENT }
    });
    if (!res || !res.ok) {
      if (res && res.status === 404) {
        summaryCache.set(cleanTitle, null);
      }
      return null;
    }
    const data = await res.json();
    summaryCache.set(cleanTitle, data);
    return data;
  } catch (e) {
    return null;
  }
}

async function searchWikipedia(query) {
  const cleanQuery = query.trim();
  if (searchCache.has(cleanQuery)) {
    return searchCache.get(cleanQuery);
  }

  try {
    const url = `https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=${encodeURIComponent(cleanQuery)}&utf8=&format=json`;
    const res = await fetchWithRetry(url, {
      headers: { 'User-Agent': USER_AGENT }
    });
    if (!res || !res.ok) {
      return [];
    }
    const data = await res.json();
    const results = data.query?.search || [];
    searchCache.set(cleanQuery, results);
    return results;
  } catch (e) {
    return [];
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

function isFilmSummary(summary) {
  if (!summary || summary.type === 'disambiguation') return false;
  
  const desc = (summary.description || '').toLowerCase();
  const extract = (summary.extract || '').toLowerCase();
  const title = (summary.titles?.canonical || '').toLowerCase();

  // If description specifically states it's another medium with NO film mention
  const explicitOtherMedia = ['novel by', 'book by', 'soundtrack album', 'studio album', 'song by', 'single by', 'character', 'television series', 'tv series', 'video game'];
  const isOther = explicitOtherMedia.some(m => desc.includes(m)) && !desc.includes('film') && !desc.includes('movie');
  if (isOther) return false;

  // Positive signs
  if (title.endsWith('_(film)') || title.endsWith('_film)')) return true;
  if (desc.includes('film') || desc.includes('movie') || desc.includes('directed by')) return true;
  if (extract.includes('is a film') || extract.includes('is an animated film') || extract.includes('directed by') || extract.includes('starring')) return true;

  return false;
}

// Special merged titles dictionary
const MERGED_TITLES_MAP = {
  // 2000
  'Citizen Toxie: The Toxic Avenger IVCommitted': ['Citizen Toxie: The Toxic Avenger IV', 'Committed'],
  'Mission: Impossible IIMVP: Most Valuable Primate': ['Mission: Impossible II', 'MVP: Most Valuable Primate'],
  'Titan A.E.Traffic': ['Titan A.E.', 'Traffic'],
  
  // 2001
  '15 Minutes3000 Miles to Graceland': ['15 Minutes', '3000 Miles to Graceland'],
  'CQCrocodile Dundee in Los Angeles': ['CQ', 'Crocodile Dundee in Los Angeles'],
  'Jason XJay and Silent Bob Strike Back': ['Jason X', 'Jay and Silent Bob Strike Back'],
  'Jurassic Park IIIJust Visiting': ['Jurassic Park III', 'Just Visiting'],
  'K-PAXKate & Leopold': ['K-PAX', 'Kate & Leopold'],
  'L.I.E.Lara Croft: Tomb Raider': ['L.I.E.', 'Lara Croft: Tomb Raider'],

  // 2002
  'Adaptation.Ali G Indahouse': ['Adaptation.', 'Ali G Indahouse'],
  'Blade IIBlood Work': ['Blade II', 'Blood Work'],
  'John QJuwanna Mann': ['John Q', 'Juwanna Mann'],
  'Men in Black IIMinority Report': ['Men in Black II', 'Minority Report'],

  // 2003
  'Bad Boys IIBad Santa': ['Bad Boys II', 'Bad Santa'],
  'Fear XFlywheel': ['Fear X', 'Flywheel'],
  'S.W.A.T.Saints and Soldiers': ['S.W.A.T.', 'Saints and Soldiers'],

  // 2004
  'Pusher IIRaise Your Voice': ['Pusher II', 'Raise Your Voice'],

  // 2005
  "Get Rich or Die Tryin'Goal! The Dream Begins": ["Get Rich or Die Tryin'", "Goal! The Dream Begins"],
  'Good Night, and Good Luck.Green Street Hooligans': ['Good Night, and Good Luck.', 'Green Street Hooligans'],
  'Saw IISeparate Lies': ['Saw II', 'Separate Lies'],
  'The Kid & IThe Legend of Zorro': ['The Kid & I', 'The Legend of Zorro'],
  'White Noisex': ['White Noise', 'xXx: State of the Union'],

  // 2006
  '10 Items or Less16 Blocks': ['10 Items or Less', '16 Blocks'],
  'ATLBabel': ['ATL', 'Babel'],
  'Clerks IIClick': ['Clerks II', 'Click'],
  'Mission: Impossible IIIMy Super Ex-Girlfriend': ['Mission: Impossible III', 'My Super Ex-Girlfriend'],
  'RVSaw IIIScary Movie 4': ['RV', 'Saw III', 'Scary Movie 4'],

  // 2008
  'Babylon A.D.Bad Biology': ['Babylon A.D.', 'Bad Biology'],
  'Saw VSemi-Pro': ['Saw V', 'Semi-Pro'],

  // 2009
  '17 Again2012': ['17 Again', '2012'],
  '500 Days of Summer9 Miles Down9': ['500 Days of Summer', '9 Miles Down', '9'],
  'Halloween IIHannah Montana: The Movie': ['Halloween II', 'Hannah Montana: The Movie'],
  'Saw VIShank': ['Saw VI', 'Shank'],

  // 2010
  '13 (I)2001 Maniacs: Field of Screams6 Souls': ['13', '2001 Maniacs: Field of Screams', '6 Souls'],
  'Easy AEat Pray Love': ['Easy A', 'Eat Pray Love'],
  'Hatchet IIHenry\'s Crime': ['Hatchet II', 'Henry\'s Crime'],
  'DPredators': ['Predators'],
  'REDRed White & Blue': ['RED', 'Red White & Blue'],
  'DScott Pilgrim vs. the World': ['Scott Pilgrim vs. the World'],
  'DStone': ['Stone'],

  // 2013
  'World War ZWe\'re the Millers': ['World War Z', 'We\'re the Millers'],
  'Snowpiercer12 Years a Slave': ['Snowpiercer', '12 Years a Slave'],
  'This Is the End2 Guns': ['This Is the End', '2 Guns'],
  'Escape Plan47 Ronin': ['Escape Plan', '47 Ronin'],
  'The Hangover Part IIIScary Movie 5': ['The Hangover Part III', 'Scary Movie 5'],
  'The Call42': ['The Call', '42'],
  'R.I.P.D.Percy Jackson: Sea of Monsters': ['R.I.P.D.', 'Percy Jackson: Sea of Monsters'],
  'CBGBLife of a King': ['CBGB', 'Life of a King'],
  'The Lifeguard21 & Over': ['The Lifeguard', '21 & Over'],
  'Hatchet IIIIn': ['Hatchet III'],
  'DMagic Magic': ['Magic Magic'],
  'Interior. Leather Bar.Red Wing': ['Interior. Leather Bar.', 'Red Wing'],
  'Legendary12 Rounds 2: Reloaded': ['Legendary', '12 Rounds 2: Reloaded'],
  'The Last Exorcism Part IIJack the Giant Killer': ['The Last Exorcism Part II', 'Jack the Giant Killer']
};

async function resolveMovieUrl(rawTitle, year) {
  // Strategy 1: Title (year film)
  if (year) {
    const s1 = await getSummary(`${rawTitle} (${year} film)`);
    if (isFilmSummary(s1)) {
      return { url: s1.content_urls.desktop.page, method: 'year_film', desc: s1.description };
    }
  }

  // Strategy 2: Title (film)
  const s2 = await getSummary(`${rawTitle} (film)`);
  if (isFilmSummary(s2)) {
    const s2YearMatch = (s2.description + ' ' + s2.extract).match(/\b(19\d\d|20\d\d)\b/);
    if (!year || !s2YearMatch || Math.abs(parseInt(s2YearMatch[1], 10) - year) <= 1) {
      return { url: s2.content_urls.desktop.page, method: 'film', desc: s2.description };
    }
  }

  // Strategy 3: Exact title
  const s3 = await getSummary(rawTitle);
  if (isFilmSummary(s3)) {
    return { url: s3.content_urls.desktop.page, method: 'direct', desc: s3.description };
  }

  // Strategy 4: Search Wikipedia with year
  const query = year ? `"${rawTitle}" ${year} film` : `"${rawTitle}" film`;
  const searchResults = await searchWikipedia(query);
  for (const item of searchResults.slice(0, 5)) {
    const s = await getSummary(item.title);
    if (isFilmSummary(s)) {
      return { url: s.content_urls.desktop.page, method: 'search', desc: s.description };
    }
  }

  // Strategy 5: Relaxed search
  const queryRelaxed = year ? `${rawTitle} ${year} film` : `${rawTitle} film`;
  const searchRelaxed = await searchWikipedia(queryRelaxed);
  for (const item of searchRelaxed.slice(0, 5)) {
    const s = await getSummary(item.title);
    if (isFilmSummary(s)) {
      return { url: s.content_urls.desktop.page, method: 'search_relaxed', desc: s.description };
    }
  }

  // Strategy 6: Search without year constraint if still not found
  if (year) {
    const searchNoYear = await searchWikipedia(`"${rawTitle}" film`);
    for (const item of searchNoYear.slice(0, 3)) {
      const s = await getSummary(item.title);
      if (isFilmSummary(s)) {
        return { url: s.content_urls.desktop.page, method: 'search_noyear', desc: s.description };
      }
    }
  }

  return null;
}

async function processFile(inputFilePath, outputFilePath) {
  const filename = path.basename(inputFilePath);
  console.log(`\n======================================================`);
  console.log(`Processing: ${filename}`);
  console.log(`======================================================`);

  // Detect year from filename: e.g. "Movie_List_2000.xlsx" -> 2000
  let targetYear = null;
  const yearMatch = filename.match(/(?:^|\D)(19\d\d|20\d\d)(?:\D|$)/);
  if (yearMatch) {
    targetYear = parseInt(yearMatch[1], 10);
  }
  console.log(`Detected target year: ${targetYear || 'None'}`);

  const wb = XLSX.readFile(inputFilePath);
  const firstSheetName = wb.SheetNames[0];
  const ws = wb.Sheets[firstSheetName];
  const rawRows = XLSX.utils.sheet_to_json(ws, { header: 1 });

  if (rawRows.length === 0) {
    console.log(`Empty sheet! Skipping.`);
    return;
  }

  const header = rawRows[0];
  const newRows = [header];
  let fixedCount = 0;
  let alreadyValidCount = 0;
  let splitCount = 0;
  let unresolvedCount = 0;

  for (let i = 1; i < rawRows.length; i++) {
    const row = rawRows[i];
    const rawTitle = (row[0] || '').toString().trim();
    const currentUrl = (row[1] || '').toString().trim();

    if (!rawTitle) continue;

    // Check if this is a known merged row
    if (MERGED_TITLES_MAP[rawTitle]) {
      const splitTitles = MERGED_TITLES_MAP[rawTitle];
      for (const spTitle of splitTitles) {
        process.stdout.write(`  [SPLIT] Resolving: "${spTitle}" (${targetYear || 'no year'})... `);
        const resolved = await resolveMovieUrl(spTitle, targetYear);
        if (resolved) {
          console.log(`OK -> ${resolved.url}`);
          newRows.push([spTitle, resolved.url]);
        } else {
          console.log(`Keeping base URL`);
          newRows.push([spTitle, `https://en.wikipedia.org/wiki/${encodeURIComponent(spTitle.replace(/ /g, '_'))}`]);
        }
      }
      splitCount++;
      fixedCount++;
      continue;
    }

    // Try extracting year from title if not in filename (e.g. "Focus (2015)")
    let rowYear = targetYear;
    const titleYearMatch = rawTitle.match(/\((19\d\d|20\d\d)\)/);
    if (titleYearMatch) {
      rowYear = parseInt(titleYearMatch[1], 10);
    }
    const cleanTitle = rawTitle.replace(/\s*\((19\d\d|20\d\d)\)\s*$/, '').trim();

    // Check current URL validity
    let isCurrentUrlValid = false;
    let currentSummary = null;
    const currentWikiTitle = extractWikiTitle(currentUrl);

    if (currentWikiTitle) {
      currentSummary = await getSummary(currentWikiTitle);
      if (currentSummary && isFilmSummary(currentSummary)) {
        const titleYearMatch = currentWikiTitle.match(/\b(19\d\d|20\d\d)\b/);
        const hasConflict = rowYear && titleYearMatch && Math.abs(parseInt(titleYearMatch[1], 10) - rowYear) > 1;
        if (!hasConflict) {
          isCurrentUrlValid = true;
        }
      }
    }

    if (isCurrentUrlValid && currentSummary) {
      alreadyValidCount++;
      const canonicalUrl = currentSummary.content_urls?.desktop?.page || currentUrl;
      newRows.push([rawTitle, canonicalUrl]);
    } else {
      // Need resolution!
      process.stdout.write(`  Resolving: "${rawTitle}" (${rowYear || 'no year'})... `);
      const resolved = await resolveMovieUrl(cleanTitle, rowYear);
      if (resolved) {
        fixedCount++;
        console.log(`FIXED -> ${resolved.url} [${resolved.method}]`);
        newRows.push([rawTitle, resolved.url]);
      } else {
        unresolvedCount++;
        console.log(`FAILED! Keeping existing.`);
        newRows.push([rawTitle, currentUrl]);
      }
    }

    // Delay to prevent 429
    await sleep(75);
  }

  // Write new worksheet
  const newWs = XLSX.utils.aoa_to_sheet(newRows);
  const newWb = XLSX.utils.book_new();
  XLSX.utils.book_append_sheet(newWb, newWs, firstSheetName);
  XLSX.writeFile(newWb, outputFilePath);

  console.log(`--- Summary for ${filename} ---`);
  console.log(`Total Movies: ${newRows.length - 1}`);
  console.log(`Already Valid: ${alreadyValidCount}`);
  console.log(`Fixed / Resolved: ${fixedCount} (Splits: ${splitCount})`);
  console.log(`Unresolved: ${unresolvedCount}`);
  console.log(`Saved to: ${outputFilePath}`);

  return {
    filename,
    total: newRows.length - 1,
    alreadyValid: alreadyValidCount,
    fixed: fixedCount,
    unresolved: unresolvedCount
  };
}

async function run() {
  const rawDir = 'E:/WebRecorder/tools/Excel_Fixer/raw';
  const fixedDir = 'E:/WebRecorder/tools/Excel_Fixer/fixed';
  if (!fs.existsSync(fixedDir)) {
    fs.mkdirSync(fixedDir, { recursive: true });
  }

  const files = fs.readdirSync(rawDir).filter(f => f.endsWith('.xlsx'));
  console.log(`Found ${files.length} Excel files to process.`);

  const stats = [];
  for (const file of files) {
    const inPath = path.join(rawDir, file);
    const outPath = path.join(fixedDir, file);
    const stat = await processFile(inPath, outPath);
    if (stat) stats.push(stat);
  }

  console.log('\n======================================================');
  console.log('ALL FILES PROCESSED SUCCESSFULLY!');
  console.log('======================================================');
  console.table(stats);
}

run();
