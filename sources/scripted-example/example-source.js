var SOURCE_NAME = "Scripted Example";
var SOURCE_LANG = "en";

var CATALOG = [
    { url: "scripted://cosmos-laundromat", title: "Cosmos Laundromat" },
    { url: "scripted://tears-of-steel", title: "Tears of Steel" }
];

var VIDEO_URLS = {
    "scripted://cosmos-laundromat": "https://storage.googleapis.com/gtv-videos-bucket/sample/GoogleGiveback.mp4",
    "scripted://tears-of-steel": "https://storage.googleapis.com/gtv-videos-bucket/sample/TearsOfSteel.mp4"
};

function getPopular(page) {
    if (page > 1) {
        return JSON.stringify({ items: [], hasNextPage: false });
    }
    return JSON.stringify({ items: CATALOG, hasNextPage: false });
}

function getLatest(page) {
    return getPopular(page);
}

function search(query, page) {
    var results = [];
    for (var i = 0; i < CATALOG.length; i++) {
        if (CATALOG[i].title.toLowerCase().indexOf(query.toLowerCase()) !== -1) {
            results.push(CATALOG[i]);
        }
    }
    return JSON.stringify({ items: results, hasNextPage: false });
}

function getMediaDetails(mediaUrl) {
    return JSON.stringify({
        description: "A hand-written scripted source used to validate the ScriptedVideoSource pipeline end to end.",
        genres: ["Demo", "Scripted"],
        status: "COMPLETED"
    });
}

function getEpisodeList(mediaUrl) {
    return JSON.stringify([
        { url: mediaUrl, name: "Full video", episodeNumber: 1 }
    ]);
}

function getVideoList(episodeUrl) {
    var videoUrl = VIDEO_URLS[episodeUrl];
    return JSON.stringify([
        { url: videoUrl, quality: "Source", isM3U8: false }
    ]);
}
