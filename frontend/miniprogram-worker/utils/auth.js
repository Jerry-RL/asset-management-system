// 认证工具（工作端复用 PC 账号体系）
function isLoggedIn() {
  return !!wx.getStorageSync('accessToken');
}

function setAuth(token, user) {
  wx.setStorageSync('accessToken', token);
  wx.setStorageSync('user', user);
}

function getUser() {
  return wx.getStorageSync('user') || null;
}

module.exports = { isLoggedIn, setAuth, getUser };
