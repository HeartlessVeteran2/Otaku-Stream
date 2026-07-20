# Writing a scripted source

Scripted sources let you add a new content source **at runtime** — no fork, no rebuild. Paste a
URL to a `.js` file under **Settings → Manage sources** and it becomes a fully-fledged source:
it shows up in Catalog and the home rails, is searchable, and plays through the normal pipeline.

Scripts run inside an embedded [Mozilla Rhino](https://github.com/mozilla/rhino) interpreter in
**interpreted mode**, sandboxed: the only capability exposed to a script is a
`httpGet(url, headersJson?)` global. No filesystem, no reflection, no Java interop.

## The contract

A source script defines two constants and six functions. Each function returns
`JSON.stringify(...)` of the corresponding shape:

```js
var SOURCE_NAME = "My Source";
var SOURCE_LANG = "en";

// Paged listings — all three share one result shape.
function getPopular(page) {
    return JSON.stringify({
        items: [{ url: "...", title: "...", coverUrl: "..." }],
        hasNextPage: false,
    });
}
function getLatest(page) { /* same shape as getPopular */ }
function search(query, page) { /* same shape as getPopular */ }

// Details for one media item.
function getMediaDetails(mediaUrl) {
    return JSON.stringify({ description: "...", genres: [], status: "COMPLETED" });
}

// Episodes for one media item.
function getEpisodeList(mediaUrl) {
    return JSON.stringify([{ url: "...", name: "Episode 1", episodeNumber: 1 }]);
}

// Playable streams for one episode.
function getVideoList(episodeUrl) {
    return JSON.stringify([{ url: "...", quality: "720p", isM3U8: false, headers: {} }]);
}
```

Notes:

- `url` fields are opaque to the app — they're passed back to your later functions verbatim, so
  encode whatever you need in them (IDs, slugs, full URLs).
- `headers` on a video are sent with every media request for that stream — use them for
  referer/user-agent requirements.
- `isM3U8: true` forces HLS handling when the URL doesn't end in `.m3u8`.
- Throwing (or returning malformed JSON) from one function fails that call gracefully; it won't
  crash the app or blank other sources.

## Reference implementation

[`sources/scripted-example/example-source.js`](../sources/scripted-example/example-source.js)
is a complete working source against public-domain sample videos — copy it as a starting point.

## Hosting and installing

Host the file anywhere that serves raw text over HTTPS (a raw GitHub URL works fine), then add
its URL under **Settings → Manage sources**. The script is fetched once and stored in the app's
database; remove or re-add it from the same screen to update.
