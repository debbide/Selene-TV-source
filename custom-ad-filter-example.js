// LunaTV 自定义去广告规则（示例）
// 用法：粘贴到 LunaTV 管理后台「自定义去广告配置」，版本号 +1，保存。
// Selene-TV / 网页端会按版本号自动拉取执行，无需重新编译 App。
//
// 原理：赌博广告是固定成片灌进 playlist 的，每次出现时长序列一模一样
// （30fps 整帧编码）。按「DISCONTINUITY 块内时长序列」精确匹配，零误伤。
// 新广告样式出现时：抓 playlist，把广告块的 EXTINF 时长按顺序抄进
// AD_SIGNATURES 加一行，版本号 +1 即可。

function filterAdsFromM3U8(type, m3u8Content) {
  if (!m3u8Content) return m3u8Content;
  var lines = m3u8Content.split('\n');

  // ---- 1) 广告块签名：块内时长序列精确匹配 ----
  var AD_SIGNATURES = [
    [5.567, 2.933, 6.667],  // 创意 A（6 位小数写法 5.566667/2.933333/6.666667 是同一指纹）
    [5.567, 2.933, 5.700],  // 创意 B
    [3.333, 1.533],         // 创意 C
  ];
  var TOL = 0.001; // 3 位 / 6 位小数写法归一化容差

  function durationsOf(blockLines) {
    var ds = [];
    for (var i = 0; i < blockLines.length; i++) {
      var m = /^#EXTINF:([0-9.]+)/.exec(blockLines[i]);
      if (m) ds.push(parseFloat(m[1]));
    }
    return ds;
  }
  function sigMatch(ds) {
    for (var s = 0; s < AD_SIGNATURES.length; s++) {
      var sig = AD_SIGNATURES[s];
      if (sig.length !== ds.length) continue;
      var ok = true;
      for (var i = 0; i < sig.length; i++) {
        if (Math.abs(sig[i] - ds[i]) > TOL) { ok = false; break; }
      }
      if (ok) return true;
    }
    return false;
  }

  var discIdx = [];
  for (var i = 0; i < lines.length; i++) {
    if (lines[i].indexOf('#EXT-X-DISCONTINUITY') !== -1) discIdx.push(i);
  }
  var drop = {};
  for (var b = 0; b < discIdx.length; b++) {
    var start = discIdx[b];
    var end = (b + 1 < discIdx.length) ? discIdx[b + 1] : lines.length;
    var ds = durationsOf(lines.slice(start + 1, end));
    if (ds.length > 0 && sigMatch(ds)) {
      for (var k = start; k < end; k++) {
        if (lines[k].indexOf('#EXT-X-ENDLIST') !== -1) continue; // 别吞掉尾标记
        drop[k] = true;
      }
    }
  }
  var kept = [];
  for (var i = 0; i < lines.length; i++) if (!drop[i]) kept.push(lines[i]);
  lines = kept;

  // ---- 2) 关键字规则（网页端默认） ----
  var keywords = ['sponsor', '/ad/', '/ads/', 'advert', 'advertisement', '/adjump', 'redtraffic'];
  var out = [];
  var i = 0;
  while (i < lines.length) {
    var line = lines[i];
    if (line.indexOf('#EXT-X-DISCONTINUITY') !== -1) { i++; continue; }
    if (line.indexOf('#EXTINF:') === 0 && i + 1 < lines.length) {
      var next = lines[i + 1].toLowerCase();
      var hit = false;
      for (var w = 0; w < keywords.length; w++) {
        if (next.indexOf(keywords[w]) !== -1) { hit = true; break; }
      }
      if (hit) { i += 2; continue; }
    }
    out.push(line); i++;
  }
  return out.join('\n');
}
