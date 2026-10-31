// 播报播放器：取后端分段计划 → 逐段合成 MP3 → 落临时文件 → InnerAudioContext 顺序播放。
// 播放当前段时预取下一段，段间停顿尽量短；stop/新一轮播放会用 session 号作废所有在途请求。
const api = require('./api');

const fs = wx.getFileSystemManager();

function writeAudioFile(arrayBuffer, session, index) {
  return new Promise((resolve, reject) => {
    const filePath = wx.env.USER_DATA_PATH + '/butler-speech-' + session + '-' + index + '.mp3';
    fs.writeFile({
      filePath,
      data: arrayBuffer,
      success: () => resolve(filePath),
      fail: (err) => reject(new Error('音频保存失败：' + (err.errMsg || ''))),
    });
  });
}

function createSpeechPlayer(onState) {
  const audio = wx.createInnerAudioContext();
  audio.obeyMuteSwitch = false;

  let session = 0;
  let destroyed = false;
  let chunks = [];
  let segmentFiles = {}; // index -> Promise<filePath>
  let current = -1;
  let state = 'idle';

  function emit(nextState, extra) {
    state = nextState;
    if (onState) {
      onState(Object.assign({ state, index: current, total: chunks.length }, extra || {}));
    }
  }

  function loadSegment(index, mySession) {
    if (segmentFiles[index]) return segmentFiles[index];
    segmentFiles[index] = api.synthesizeSpeechSegment(chunks[index])
      .then((buffer) => writeAudioFile(buffer, mySession, index))
      .catch((err) => {
        delete segmentFiles[index];
        throw err;
      });
    return segmentFiles[index];
  }

  function playIndex(index, mySession) {
    current = index;
    emit('loading');
    loadSegment(index, mySession).then(
      (filePath) => {
        if (destroyed || mySession !== session) return;
        audio.src = filePath;
        audio.play();
        emit('playing');
        // 预取下一段：当前段播完即可无缝接上
        if (index + 1 < chunks.length) {
          loadSegment(index + 1, mySession).catch(() => {});
        }
      },
      (err) => {
        if (destroyed || mySession !== session) return;
        emit('error', { message: err.message });
      }
    );
  }

  audio.onEnded(() => {
    if (destroyed) return;
    if (current + 1 < chunks.length) {
      playIndex(current + 1, session);
    } else {
      current = -1;
      emit('ended');
    }
  });

  audio.onError((err) => {
    if (destroyed) return;
    emit('error', { message: '播放失败：' + ((err && err.errMsg) || '音频异常') });
  });

  function play(text) {
    const mySession = ++session;
    chunks = [];
    segmentFiles = {};
    current = -1;
    emit('loading');
    api.planSpeech(text).then(
      (plan) => {
        if (destroyed || mySession !== session) return;
        chunks = plan.chunks || [];
        if (!chunks.length) {
          emit('error', { message: '没有可播报的内容' });
          return;
        }
        playIndex(0, mySession);
      },
      (err) => {
        if (destroyed || mySession !== session) return;
        emit('error', { message: err.message });
      }
    );
  }

  function pause() {
    if (state !== 'playing') return;
    audio.pause();
    emit('paused');
  }

  function resume() {
    if (state !== 'paused') return;
    audio.play();
    emit('playing');
  }

  function stop() {
    session += 1;
    audio.stop();
    chunks = [];
    segmentFiles = {};
    current = -1;
    emit('idle');
  }

  function destroy() {
    destroyed = true;
    session += 1;
    try { audio.destroy(); } catch (e) { /* 已销毁时忽略 */ }
  }

  return {
    play, pause, resume, stop, destroy,
    getState: () => state,
  };
}

module.exports = { createSpeechPlayer };
