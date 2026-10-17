function filterAdsFromM3U8(type, m3u8Content) {
  if (!m3u8Content) return m3u8Content;
  var lines = m3u8Content.split('\n');

  // 1) 广告块指纹：DISCONTINUITY 对夹 + 循环小数时长（30fps 整帧广告编码器指纹）
  var discIdx = [];
  for (var i = 0; i < lines.length; i++) {
    if (lines[i].indexOf('#EXT-X-DISCONTINUITY') !== -1) discIdx.push(i);
  }
  var drop = {};
  for (var b = 0; b < discIdx.length; b++) {
    var start = discIdx[b];
    var end = (b + 1 < discIdx.length) ? discIdx[b + 1] : lines.length;
    var suspicious = false;
    for (var j = start + 1; j < end; j++) {
      var ln = lines[j];
      if (ln.indexOf('#EXTINF:') === 0 && (ln.indexOf('33333') !== -1 || ln.indexOf('66667') !== -1)) {
        suspicious = true; break;
      }
    }
    if (suspicious) for (var k = start; k < end; k++) drop[k] = true;
  }
  var kept = [];
  for (var i = 0; i < lines.length; i++) if (!drop[i]) kept.push(lines[i]);
  lines = kept;

  // 2) 关键字规则（网页端默认）
  var keywords = ['sponsor', '/ad/', '/ads/', 'advert', 'advertisement', '/adjump', 'redtraffic'];
  var out = [];
  var i = 0;
  while (i < lines.length) {
    var line = lines[i];
    if (line.indexOf('#EXT-X-DISCONTINUITY') !== -1) { i++; continue; }
    if (line.indexOf('#EXTINF:') !== -1 && i + 1 < lines.length) {
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
