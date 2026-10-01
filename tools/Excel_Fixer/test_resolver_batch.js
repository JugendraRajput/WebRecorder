const items = [
  { title: "All the Pretty Horses", year: 2000 },
  { title: "Beautiful Creatures", year: 2000 },
  { title: "Bread and Roses", year: 2000 },
  { title: "Bring It On", year: 2000 },
  { title: "Crime + Punishment in Suburbia", year: 2000 },
  { title: "Frequency", year: 2000 },
  { title: "George Washington", year: 2000 },
  { title: "Loser", year: 2000 },
  { title: "Maybe Baby", year: 2000 },
  { title: "Nora", year: 2000 },
  { title: "Our Lips Are Sealed", year: 2000 },
  { title: "Pandaemonium", year: 2000 },
  { title: "Panic", year: 2000 },
  { title: "Paragraph 175", year: 2000 },
  { title: "Quills", year: 2000 },
  { title: "Relative Values", year: 2000 },
  { title: "Road Trip", year: 2000 },
  { title: "Snow Day", year: 2000 },
  { title: "Sunset Strip", year: 2000 },
  { title: "Track down", year: 2000 },
  { title: "The American Nightmare", year: 2000 },
  { title: "The Cell", year: 2000 },
  { title: "The Eyes of Tammy Faye", year: 2000 },
  { title: "The in Crowd", year: 2000 },
  { title: "The Smokers", year: 2000 },
  { title: "Tully", year: 2000 },
  { title: "Vatel", year: 2000 },
  { title: "Vulgar", year: 2000 },
  { title: "Whipped", year: 2000 },
  { title: "Woman on Top", year: 2000 }
];

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

async function searchWikipedia(query) {
  try {
    const url = `https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=${encodeURIComponent(query)}&utf8=&format=json`;
    const res = await fetch(url, {
      headers: { 'User-Agent': 'WebRecorderMovieFixer/1.0 (contact@jdworks.in)' }
    });
    if (!res.ok) return [];
    const data = await res.json();
    return data.query?.search || [];
  } catch (e) {
    return [];
  }
}

function isFilmSummary(summary) {
  if (!summary || summary.type === 'disambiguation') return false;
  const desc = (summary.description || '').toLowerCase();
  const extract = (summary.extract || '').toLowerCase();
  const text = `${desc} ${extract}`;
  return text.includes('film') || text.includes('movie') || text.includes('directed by');
}

async function resolveMovie(rawTitle, year) {
  // Strategy 1: Title (year film)
  if (year) {
    const s1 = await getSummary(`${rawTitle} (${year} film)`);
    if (isFilmSummary(s1)) return { url: s1.content_urls.desktop.page, method: 'year_film', desc: s1.description };
  }

  // Strategy 2: Title (film)
  const s2 = await getSummary(`${rawTitle} (film)`);
  if (isFilmSummary(s2)) return { url: s2.content_urls.desktop.page, method: 'film', desc: s2.description };

  // Strategy 3: Title (film title itself)
  const s3 = await getSummary(rawTitle);
  if (isFilmSummary(s3)) return { url: s3.content_urls.desktop.page, method: 'direct', desc: s3.description };

  // Strategy 4: Search
  const query = year ? `"${rawTitle}" ${year} film` : `"${rawTitle}" film`;
  const results = await searchWikipedia(query);
  for (const item of results.slice(0, 5)) {
    const s = await getSummary(item.title);
    if (isFilmSummary(s)) {
      return { url: s.content_urls.desktop.page, method: 'search', desc: s.description, matchedTitle: item.title };
    }
  }

  // Strategy 5: Relaxed search without quotes
  const relaxedQuery = year ? `${rawTitle} ${year} film` : `${rawTitle} film`;
  const relaxedResults = await searchWikipedia(relaxedQuery);
  for (const item of relaxedResults.slice(0, 5)) {
    const s = await getSummary(item.title);
    if (isFilmSummary(s)) {
      return { url: s.content_urls.desktop.page, method: 'search_relaxed', desc: s.description, matchedTitle: item.title };
    }
  }

  return null;
}

async function runTest() {
  for (const item of items) {
    const resolved = await resolveMovie(item.title, item.year);
    if (resolved) {
      console.log(`[RESOLVED] "${item.title}" -> ${resolved.url} [${resolved.method} | ${resolved.desc}]`);
    } else {
      console.log(`[FAILED]   "${item.title}"`);
    }
  }
}

runTest();
