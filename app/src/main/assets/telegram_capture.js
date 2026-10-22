(function () {
  "use strict";
  var token = "__STILL_TOKEN__";
  if (window.__stillTelegramCaptureRunning) return;
  window.__stillTelegramCaptureRunning = token;
  window.__stillTelegramCaptureToken = token;
  var controller = null;
  var cancelled = false;

  window.__stillTelegramCaptureAbort = function (abortToken) {
    if (abortToken && window.__stillTelegramCaptureToken !== abortToken) return;
    cancelled = true;
    if (controller) controller.abort();
  };

  function visible(element) {
    var rect = element.getBoundingClientRect();
    return rect.width > 0 && rect.height > 0 && rect.bottom > 0 &&
      rect.top < window.innerHeight && getComputedStyle(element).visibility !== "hidden";
  }

  function sourceOf(video) {
    if (!video) return "";
    var source = video.querySelector("source");
    return video.currentSrc || video.src || (source && source.src) || "";
  }

  function findVideo() {
    var viewer = document.querySelector(".media-viewer-whole");
    var candidates = viewer ? Array.prototype.slice.call(viewer.querySelectorAll("video")) : [];
    var video = candidates.filter(function (item) {
      return visible(item) && sourceOf(item);
    })[0];
    if (video) return video;
    candidates = Array.prototype.slice.call(document.querySelectorAll("video"));
    return candidates.filter(function (item) {
      return visible(item) && sourceOf(item);
    })[0];
  }

  function base64(bytes) {
    var text = "";
    for (var offset = 0; offset < bytes.length; offset += 32768) {
      text += String.fromCharCode.apply(null, bytes.subarray(offset, offset + 32768));
    }
    return window.btoa(text);
  }

  function streamResponse(response, expected, total) {
    if (!response.body) {
      return response.arrayBuffer().then(function (buffer) {
        var bytes = new Uint8Array(buffer);
        if (!bytes.length || (expected && bytes.length !== expected)) {
          throw new Error("Telegram returned an incomplete video");
        }
        StillTelegram.chunk(token, base64(bytes));
        StillTelegram.progress(token, bytes.length, total || bytes.length);
        return bytes.length;
      });
    }

    var reader = response.body.getReader();
    var received = 0;
    function next() {
      if (cancelled) throw new Error("Cancelled");
      return reader.read().then(function (part) {
        if (part.done) return received;
        if (expected && received + part.value.length > expected) {
          throw new Error("Telegram sent too much video data");
        }
        StillTelegram.chunk(token, base64(part.value));
        received += part.value.length;
        StillTelegram.progress(token, received, total || 0);
        return new Promise(function (resolve) { setTimeout(resolve, 0); }).then(next);
      });
    }
    return next().then(function (bytes) {
      if (!bytes || (expected && bytes !== expected)) {
        throw new Error("Telegram returned an incomplete video");
      }
      return bytes;
    });
  }

  function responseMime(response) {
    var mime = (response.headers.get("Content-Type") || "video/mp4").split(";")[0].toLowerCase();
    if (mime.indexOf("video/") !== 0) throw new Error("Telegram did not return a video");
    return mime;
  }

  function contentRange(response) {
    var value = response.headers.get("Content-Range") || "";
    var match = /^bytes (\d+)-(\d+)\/(\d+)$/.exec(value);
    if (!match) throw new Error("Telegram returned an invalid video range");
    var start = Number(match[1]);
    var end = Number(match[2]);
    var total = Number(match[3]);
    if (!Number.isSafeInteger(start) || !Number.isSafeInteger(end) ||
        !Number.isSafeInteger(total) || end < start || total <= end) {
      throw new Error("Telegram returned an invalid video range");
    }
    return { start: start, end: end, total: total };
  }

  function length(response) {
    if (response.headers.has("Content-Encoding")) return 0;
    var value = response.headers.get("Content-Length");
    if (value === null || !/^\d+$/.test(value)) return 0;
    var number = Number(value);
    return Number.isSafeInteger(number) && number > 0 ? number : 0;
  }

  Promise.resolve()
    .then(function () {
      var video = findVideo();
      var source = sourceOf(video);
      if (!source) throw new Error("Open a Telegram video first");

      var offset = 0;
      var total = 0;
      var began = false;

      function nextPart() {
        if (cancelled) throw new Error("Cancelled");
        controller = new AbortController();
        var end = Math.min(offset + 512 * 1024, total ? total - 1 : offset + 512 * 1024);
        return fetch(source, {
          credentials: "include",
          headers: { Range: "bytes=" + offset + "-" + end },
          signal: controller.signal
        }).then(function (response) {
          if (response.status !== 200 && response.status !== 206) {
            throw new Error("Telegram did not return the video");
          }
          var mime = responseMime(response);
          if (response.status === 206) {
            var range = contentRange(response);
            if (range.start !== offset || (total && range.total !== total)) {
              throw new Error("Telegram returned an inconsistent video range");
            }
            total = range.total;
            if (!began) {
              StillTelegram.begin(token, mime, total);
              began = true;
            }
            return streamResponse(response, range.end - range.start + 1, total)
              .then(function () {
                offset = range.end + 1;
                if (offset < total) return nextPart();
              });
          }

          if (offset !== 0) throw new Error("Telegram stopped supporting video ranges");
          total = length(response);
          if (!began) {
            StillTelegram.begin(token, mime, total);
            began = true;
          }
          return streamResponse(response, total, total);
        });
      }

      return nextPart().then(function () {
        if (!began) throw new Error("Telegram returned no video");
        StillTelegram.finish(token);
      });
    })
    .catch(function (error) {
      StillTelegram.fail(token, error && error.message ? error.message : String(error));
    })
    .then(function () {
      if (window.__stillTelegramCaptureToken === token) {
        window.__stillTelegramCaptureAbort = null;
        window.__stillTelegramCaptureRunning = false;
        window.__stillTelegramCaptureToken = null;
      }
    });
})();
