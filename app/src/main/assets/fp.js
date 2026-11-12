(function () {
  'use strict';
  var L = (typeof __PC_LEVEL === 'number') ? __PC_LEVEL : 1; // 1 Standard, 2 Strict, 3 Maximum
  if (self.__pcFp) return; try { Object.defineProperty(self, '__pcFp', { value: 1 }); } catch (e) {}
  function def(o, k, v) { try { Object.defineProperty(o, k, { get: function () { return v; }, configurable: true }); } catch (e) {} }
  var seed = (Math.random() * 4294967296) >>> 0;
  function rng() { seed |= 0; seed = seed + 0x6D2B79F5 | 0; var t = Math.imul(seed ^ seed >>> 15, 1 | seed); t = t + Math.imul(t ^ t >>> 7, 61 | t) ^ t; return ((t ^ t >>> 14) >>> 0) / 4294967296; }
  var nav = navigator, NP = Object.getPrototypeOf(nav);

  // Standard: uniform common values instead of real hardware numbers.
  def(NP, 'hardwareConcurrency', 4);
  if ('deviceMemory' in nav) def(NP, 'deviceMemory', 4);

  if (L >= 2) {
    def(NP, 'language', 'en-US');
    def(NP, 'languages', Object.freeze(['en-US', 'en']));
    if ('getBattery' in nav) def(NP, 'getBattery', undefined);
    if ('connection' in nav) def(NP, 'connection', undefined);

    // Canvas read-back: small seeded noise (Strict) or blank (Maximum).
    var perturb = function (d) { if (L >= 3) { d.fill(0); return; } for (var i = 0; i < d.length; i += 4) { if (rng() < 0.05) d[i] ^= 1; } };
    if (self.CanvasRenderingContext2D) {
      var oGet = CanvasRenderingContext2D.prototype.getImageData, oPut = CanvasRenderingContext2D.prototype.putImageData;
      CanvasRenderingContext2D.prototype.getImageData = function () { var r = oGet.apply(this, arguments); try { perturb(r.data); } catch (e) {} return r; };
      var copy = function (c) {
        try { var w = c.width, h = c.height; if (!w || !h) return c; var t = document.createElement('canvas'); t.width = w; t.height = h;
          var x = t.getContext('2d'); x.drawImage(c, 0, 0); var d = oGet.call(x, 0, 0, w, h); perturb(d.data); oPut.call(x, d, 0, 0); return t; } catch (e) { return c; }
      };
      if (self.HTMLCanvasElement) {
        var oUrl = HTMLCanvasElement.prototype.toDataURL, oBlob = HTMLCanvasElement.prototype.toBlob;
        HTMLCanvasElement.prototype.toDataURL = function () { return oUrl.apply(copy(this), arguments); };
        HTMLCanvasElement.prototype.toBlob = function () { return oBlob.apply(copy(this), arguments); };
      }
    }
    // WebGL vendor/renderer strings.
    [self.WebGLRenderingContext, self.WebGL2RenderingContext].forEach(function (C) {
      if (!C) return; var o = C.prototype.getParameter;
      C.prototype.getParameter = function (p) { if (p === 37445) return 'Google Inc.'; if (p === 37446) return 'ANGLE (Generic Renderer)'; return o.apply(this, arguments); };
    });
    // Audio read-back noise.
    if (self.AudioBuffer) {
      var oCh = AudioBuffer.prototype.getChannelData, seen = new WeakSet();
      AudioBuffer.prototype.getChannelData = function () {
        var a = oCh.apply(this, arguments);
        if (!seen.has(a)) { seen.add(a); for (var i = 0; i < a.length; i += 97) a[i] += (rng() - 0.5) * 1e-7; }
        return a;
      };
    }
    if (self.AnalyserNode) {
      var oF = AnalyserNode.prototype.getFloatFrequencyData;
      AnalyserNode.prototype.getFloatFrequencyData = function (arr) { oF.apply(this, arguments); for (var i = 0; i < arr.length; i += 7) arr[i] += (rng() - 0.5) * 0.01; };
    }
  }

  if (L >= 3) {
    // Screen: one uniform size (page layout is unaffected; only script-visible values change).
    if (self.Screen) {
      var S = Screen.prototype;
      def(S, 'width', 412); def(S, 'height', 915); def(S, 'availWidth', 412); def(S, 'availHeight', 915);
      def(S, 'colorDepth', 24); def(S, 'pixelDepth', 24);
    }
    try {
      Object.defineProperty(self, 'outerWidth', { get: function () { return self.innerWidth; }, configurable: true });
      Object.defineProperty(self, 'outerHeight', { get: function () { return self.innerHeight; }, configurable: true });
    } catch (e) {}
    // Timezone: report UTC for the common read paths (toString/toLocale* still use local time: partial).
    var DP = Date.prototype;
    ['FullYear', 'Month', 'Date', 'Day', 'Hours', 'Minutes', 'Seconds', 'Milliseconds'].forEach(function (n) { var u = DP['getUTC' + n]; DP['get' + n] = function () { return u.call(this); }; });
    DP.getTimezoneOffset = function () { return 0; };
    if (self.Intl && Intl.DateTimeFormat) {
      var RO = Intl.DateTimeFormat.prototype.resolvedOptions;
      Intl.DateTimeFormat.prototype.resolvedOptions = function () { var r = RO.apply(this, arguments); r.timeZone = 'UTC'; return r; };
    }
    // Coarse timer (100 ms), as Tor Browser does. May break fine-grained animation code.
    if (self.Performance) { var oN = Performance.prototype.now; Performance.prototype.now = function () { return Math.floor(oN.call(this) / 100) * 100; }; }
  }
})();
