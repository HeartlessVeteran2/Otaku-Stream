// Mangayomi/AnymeX-format example extension for Otaku-Stream.
//
// Unlike the Rhino `scripted-example`, this is written in the *modern* JS that real
// AnymeX/Mangayomi extensions use — an ES class extending the injected `MProvider` base, `async`
// methods, `await`, arrow functions, template literals, `for...of` — none of which the old Rhino
// engine can parse. It runs on the QuickJS engine (`core:sources-mangayomi`).
//
// It is deliberately self-contained and offline: it exercises the runtime's `Document` DOM bridge
// against an inline HTML string and returns public-domain Blender-project clips, so the whole
// pick-a-source → details → play path can be validated with no third-party site involved. Real
// extensions replace the inline HTML with `await new Client().get(url)` + `new Document(res.body)`.

const mangayomiSources = [
  {
    name: "Mangayomi Example",
    id: 100000001,
    baseUrl: "https://example.invalid",
    lang: "en",
    typeSource: "single",
    isNsfw: false,
    version: "1.0.0",
    isManga: false,
    itemType: 1,
    sourceCodeLanguage: 1,
  },
];

// Public-domain clips (Blender Foundation open movies), keyed by the catalog link.
const VIDEO_URLS = {
  "example://cosmos-laundromat":
    "https://storage.googleapis.com/gtv-videos-bucket/sample/GoogleGiveback.mp4",
  "example://tears-of-steel":
    "https://storage.googleapis.com/gtv-videos-bucket/sample/TearsOfSteel.mp4",
};

// A tiny catalog document parsed via the host DOM bridge, proving `Document().select(...)` works.
const CATALOG_HTML = `
  <div id="catalog">
    <a class="card" href="example://cosmos-laundromat" data-cover="https://upload.wikimedia.org/wikipedia/commons/1/1e/Cosmos_Laundromat_poster.jpg">Cosmos Laundromat</a>
    <a class="card" href="example://tears-of-steel" data-cover="https://upload.wikimedia.org/wikipedia/commons/4/4a/Tos-poster.png">Tears of Steel</a>
  </div>
`;

class DefaultExtension extends MProvider {
  parseCatalog() {
    const doc = new Document(CATALOG_HTML);
    const list = doc.select("a.card").map((el) => ({
      name: el.text,
      link: el.attr("href"),
      imageUrl: el.attr("data-cover"),
    }));
    return { list, hasNextPage: false };
  }

  async getPopular(page) {
    if (page > 1) return { list: [], hasNextPage: false };
    return this.parseCatalog();
  }

  async getLatestUpdates(page) {
    return this.getPopular(page);
  }

  async search(query, page, filters) {
    const all = this.parseCatalog().list;
    const q = (query || "").toLowerCase();
    return { list: all.filter((it) => it.name.toLowerCase().includes(q)), hasNextPage: false };
  }

  async getDetail(url) {
    const match = this.parseCatalog().list.find((it) => it.link === url);
    return {
      name: match ? match.name : "Unknown",
      imageUrl: match ? match.imageUrl : "",
      description:
        "A modern-JS example extension used to validate the QuickJS Mangayomi runtime end to end.",
      genre: ["Demo", "Public Domain"],
      status: 1,
      episodes: [{ name: "Full film", url, episodeNumber: 1 }],
    };
  }

  async getVideoList(url) {
    const video = VIDEO_URLS[url];
    return [{ url: video, originalUrl: video, quality: "Source", headers: {} }];
  }

  getFilterList() {
    return [];
  }

  getSourcePreferences() {
    return [];
  }
}
