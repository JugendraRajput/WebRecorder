async function getSummary(title) {
  const url = `https://en.wikipedia.org/api/rest_v1/page/summary/${encodeURIComponent(title.replace(/ /g, '_'))}`;
  const res = await fetch(url, {
    headers: { 'User-Agent': 'WebRecorderMovieFixer/1.0 (contact@jdworks.in)' }
  });
  if (!res.ok) return null;
  return await res.json();
}

async function searchWikipedia(query) {
  const url = `https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=${encodeURIComponent(query)}&utf8=&format=json`;
  const res = await fetch(url, {
    headers: { 'User-Agent': 'WebRecorderMovieFixer/1.0 (contact@jdworks.in)' }
  });
  if (!res.ok) return [];
  const data = await res.json();
  return data.query?.search || [];
}

async function resolveMovieUrl(rawTitle, year) {
  console.log(`\nResolving for: "${rawTitle}" (${year || 'unknown'})`);
  
  // 1. Direct candidate: Title (year film)
  if (year) {
    const summaryYear = await getSummary(`${rawTitle} (${year} film)`);
    if (summaryYear && summaryYear.type === 'standard') {
      console.log(`Found direct year match: ${summaryYear.titles.canonical} -> ${summaryYear.content_urls.desktop.page}`);
      return summaryYear.content_urls.desktop.page;
    }
  }

  // 2. Direct candidate: Title (film)
  const summaryFilm = await getSummary(`${rawTitle} (film)`);
  if (summaryFilm && summaryFilm.type === 'standard') {
    console.log(`Found direct (film) match: ${summaryFilm.titles.canonical} -> ${summaryFilm.content_urls.desktop.page}`);
    return summaryFilm.content_urls.desktop.page;
  }

  // 3. Search API
  const searchQuery = year ? `"${rawTitle}" ${year} film` : `"${rawTitle}" film`;
  const searchResults = await searchWikipedia(searchQuery);
  for (const item of searchResults.slice(0, 5)) {
    const summary = await getSummary(item.title);
    if (!summary || summary.type !== 'standard') continue;
    
    const desc = (summary.description || '').toLowerCase();
    const extract = (summary.extract || '').toLowerCase();
    const titleLower = item.title.toLowerCase();
    const isFilm = desc.includes('film') || desc.includes('movie') || extract.includes('film') || extract.includes('movie');

    if (isFilm) {
      console.log(`Found via search: ${summary.titles.canonical} [${summary.description}] -> ${summary.content_urls.desktop.page}`);
      return summary.content_urls.desktop.page;
    }
  }

  return null;
}

async function run() {
  await resolveMovieUrl('Beautiful Creatures', 2000);
  await resolveMovieUrl('All the Pretty Horses', 2000);
  await resolveMovieUrl('Bamboozled', 2000);
  await resolveMovieUrl('100 Girls', 2000);
}

run();
