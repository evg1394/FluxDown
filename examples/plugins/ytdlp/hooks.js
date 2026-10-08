// FluxDown 插件：yt-dlp 通用解析 onDone 钩子（classic script，入口挂 globalThis）。
//
// 作用：下载完成后，用 ffprobe 探测**真实产物**的编码/容器，若不符合「系统播放器
// 通用」目标（H.264 视频 + AAC 音频 + MP4 容器），用 ffmpeg 转码为 H.264/AAC/mp4。
// 站点无关——对任意来源、任意视频编码（AV1/VP9/HEVC）与容器（webm/mkv）一律生效。
//
// 触发条件（缺一不可）：
//   1. 设置「优先 MP4 容器」= 开（preferMp4 !== false，默认开）；关 = 用户要
//      「最佳质量、任意编码/容器」，不转码；
//   2. flux.ffprobe + flux.ffmpeg 门面存在（manifest permissions:["ffmpeg"] 授权，
//      ffprobe 与 ffmpeg 同权限门/同牢笼），且 FluxDown 装有 ffmpeg；
//   3. ffprobe 判定产物不兼容：存在视频轨且（编码非 h264 **或** 容器非 mp4/mov 族）。
//
// 通知平面语义：fire-and-forget——失败仅记日志，绝不影响任务状态。
// 约束（见 flux.ffmpeg/flux.ffprobe 契约）：在任务 save_dir 牢笼内执行，文件一律
// 用相对名（basename，前缀 './'）。AV1/VP9/HEVC 不能 `-c copy` 进 mp4，故转码为
// H.264；源文件保留，产物为同目录 <name>.mp4，经 flux.task.recordArtifact 登记。

function baseName(p) {
  var i = Math.max(p.lastIndexOf('/'), p.lastIndexOf('\\'));
  return i >= 0 ? p.slice(i + 1) : p;
}

function stripExt(name) {
  var i = name.lastIndexOf('.');
  return i > 0 ? name.slice(0, i) : name;
}

// './' 前缀：确保 ffmpeg/ffprobe 把参数当文件路径而非选项。
function rel(name) {
  return './' + name;
}

globalThis.onDone = async (ctx) => {
  var verbose = flux.settings.verbose;

  // 用户要「最佳质量、任意编码/容器」→ 保留原始编码，不转码。
  var preferMp4 = flux.settings.preferMp4;
  if (preferMp4 === false || preferMp4 === 'false') {
    if (verbose) flux.logger.info('[ytdlp] onDone: preferMp4 关，保留原始编码');
    return;
  }

  if (!flux.ffmpeg || !flux.ffprobe) {
    if (verbose) flux.logger.warn('[ytdlp] onDone: flux.ffmpeg/ffprobe 门面不可用，跳过');
    return;
  }

  var filePath = ctx.filePath;
  if (!filePath) return;
  var inName = baseName(filePath);

  // ffprobe 探测真实产物（truthful：以磁盘上的实际文件为准，不依赖 resolve 时
  // 的「上报编码」——引擎合并/mux 后可能与上报不一致）。
  var probe = null;
  try {
    var pr = await flux.ffprobe.run({
      args: [
        '-v', 'error',
        '-print_format', 'json',
        '-show_entries', 'stream=codec_type,codec_name',
        '-show_format',
        rel(inName),
      ],
      timeoutMs: 30 * 1000,
    });
    if (pr.code !== 0 || !pr.stdout) {
      if (verbose) flux.logger.warn('[ytdlp] onDone: ffprobe 探测失败，跳过转码');
      return;
    }
    probe = JSON.parse(pr.stdout);
  } catch (e) {
    if (verbose) flux.logger.warn('[ytdlp] onDone: ffprobe 探测失败，跳过转码:', String(e));
    return;
  }

  var streams = (probe && probe.streams) || [];
  var video = null;
  var audio = null;
  for (var i = 0; i < streams.length; i++) {
    if (!streams[i]) continue;
    if (!video && streams[i].codec_type === 'video') video = streams[i];
    if (!audio && streams[i].codec_type === 'audio') audio = streams[i];
  }
  // 无视频轨：纯音频产物，本就是兼容的 m4a/mp3，无需处理。
  if (!video) {
    if (verbose) flux.logger.info('[ytdlp] onDone: 纯音频，无需转码:', inName);
    return;
  }

  var fmtName = ((probe && probe.format && probe.format.format_name) || '').toLowerCase();
  var mp4Container = /(^|,)(mp4|mov)(,|$)/.test(fmtName) || /\.(mp4|mov)$/i.test(inName);
  var h264 = /^h264$/.test(video.codec_name || '');
  var compatibleAudio = !audio || /^aac$/.test(audio.codec_name || '');
  // 引擎 mux 失败（如 AAC 无法封入 webm）时会留独立音频 sidecar，一并合并。
  var audioName = ctx.audioPath ? baseName(ctx.audioPath) : null;

  if (h264 && compatibleAudio && mp4Container && !audioName) {
    if (verbose) flux.logger.info('[ytdlp] onDone: 已是 h264+mp4，无需转码:', inName);
    return;
  }

  var avail = await flux.ffmpeg.available();
  if (!avail || !avail.available) {
    flux.logger.warn('[ytdlp] onDone: ffmpeg 未安装（可在「组件」页安装），跳过转码');
    return;
  }

  // mp4 输入不能原地覆盖；使用独立产物名，同时保留原文件。
  var outName = stripExt(inName) + (/\.mp4$/i.test(inName) ? '.compatible.mp4' : '.mp4');
  var args = ['-i', rel(inName)];

  if (audioName) args.push('-i', rel(audioName));

  args = args.concat(h264
    ? ['-c:v', 'copy']
    : ['-c:v', 'libx264', '-crf', '20', '-preset', 'veryfast']);
  var audioArgs = !audio && !audioName
    ? []
    : (compatibleAudio && !audioName
      ? ['-c:a', 'copy']
      : ['-c:a', 'aac', '-b:a', '192k']);
  args = args.concat(audioArgs).concat([
    '-movflags', '+faststart',
  ]);
  if (audioName) args.push('-map', '0:v:0', '-map', '1:a:0');
  args.push('-y', rel(outName));

  if (verbose) {
    flux.logger.info(
      '[ytdlp] onDone: 转码为 h264/mp4',
      '视频=' + (video.codec_name || '?'),
      '容器=' + (fmtName || '?'),
      inName, '→', outName
    );
  }

  var started = Date.now();
  var r;
  try {
    r = await flux.ffmpeg.run({ args: args, timeoutMs: 20 * 60 * 1000 });
  } catch (e) {
    flux.logger.error('[ytdlp] onDone: ffmpeg 调用异常:', String(e));
    return;
  }

  if (r.timedOut) {
    flux.logger.error('[ytdlp] onDone: ffmpeg 转码超时:', inName);
    return;
  }
  if (r.code !== 0) {
    flux.logger.error(
      '[ytdlp] onDone: ffmpeg 转码失败 code=' + r.code,
      (r.stderr || '').slice(-400)
    );
    return;
  }

  var secs = ((Date.now() - started) / 1000).toFixed(1);
  flux.logger.info('[ytdlp] onDone: 已转为 mp4:', outName, '(' + secs + 's，源文件保留)');

  // 登记衍生产物：删除任务文件时一并清理，旧版无此 API 时静默跳过。
  if (flux.task && flux.task.recordArtifact) {
    try {
      await flux.task.recordArtifact(outName);
    } catch (e) {
      flux.logger.warn('[ytdlp] onDone: recordArtifact 失败（不影响产物）:', String(e));
    }
  }
};
