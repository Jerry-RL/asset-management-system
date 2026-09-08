import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { api } from '@/lib/api';
import { useAuth } from '@/lib/auth';

/** 演示环境快捷账号（密码统一 admin123） */
const DEMO_ACCOUNTS = [
  { username: 'clerk', label: '办事员', hint: '外勤任务 / 催缴 / 收款' },
  { username: 'maintenance', label: '维修管理员', hint: '巡检 / 报修工单' },
  { username: 'approver', label: '审批人员', hint: '合同与流程审批' },
  { username: 'operator', label: '运营管理员', hint: '招租运营' },
] as const;

export function LoginPage() {
  const { login } = useAuth();
  const navigate = useNavigate();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [captchaId, setCaptchaId] = useState('');
  const [captchaImage, setCaptchaImage] = useState('');
  const [captchaCode, setCaptchaCode] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  const loadCaptcha = async () => {
    const data = await api.get<{ captchaId: string; captchaImage: string }>('/auth/captcha');
    setCaptchaId(data.captchaId);
    setCaptchaImage(data.captchaImage);
    setCaptchaCode('');
  };

  useEffect(() => {
    loadCaptcha().catch(() => setError('验证码加载失败，请刷新重试'));
  }, []);

  const handleFillDemo = (demoUsername: string) => {
    setUsername(demoUsername);
    setPassword('admin123');
    setError('');
  };

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    setLoading(true);
    try {
      await login(username, password, captchaId, captchaCode);
      navigate('/');
    } catch (err) {
      setError(err instanceof Error ? err.message : '登录失败');
      loadCaptcha().catch(() => undefined);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="min-h-screen flex flex-col items-center justify-center bg-gradient-to-b from-teal-600 to-teal-800 px-8">
      <div className="text-4xl font-bold text-white mb-2">资管云</div>
      <div className="text-white/80 mb-10 text-sm">资产经营管理 · 工作端</div>
      <form onSubmit={submit} className="w-full max-w-sm bg-white rounded-xl p-6 space-y-4">
        <input
          className="w-full border rounded-lg px-4 py-3"
          placeholder="账号"
          value={username}
          onChange={(e) => setUsername(e.target.value)}
          aria-label="账号"
          autoComplete="username"
        />
        <input
          className="w-full border rounded-lg px-4 py-3"
          type="password"
          placeholder="密码"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          aria-label="密码"
          autoComplete="current-password"
        />
        <div className="flex gap-2">
          <input
            className="flex-1 border rounded-lg px-4 py-3"
            placeholder="验证码"
            value={captchaCode}
            onChange={(e) => setCaptchaCode(e.target.value)}
            aria-label="验证码"
          />
          {captchaImage ? (
            <button
              type="button"
              className="shrink-0 rounded-lg border overflow-hidden"
              onClick={() => loadCaptcha().catch(() => setError('验证码加载失败'))}
              aria-label="刷新验证码"
            >
              <img src={captchaImage} alt="验证码" className="h-12 w-28 object-cover" />
            </button>
          ) : (
            <button
              type="button"
              className="shrink-0 h-12 w-28 rounded-lg border text-xs text-slate-500"
              onClick={() => loadCaptcha().catch(() => setError('验证码加载失败'))}
            >
              点击获取
            </button>
          )}
        </div>
        {error && <p className="text-red-500 text-sm">{error}</p>}
        <button
          type="submit"
          className="w-full bg-teal-600 text-white rounded-lg py-3 disabled:opacity-50"
          disabled={loading}
        >
          {loading ? '登录中...' : '登录'}
        </button>
        <div className="pt-1 border-t border-slate-100">
          <p className="text-xs text-slate-400 mb-2">演示账号（密码 admin123，点选填入）</p>
          <div className="grid grid-cols-2 gap-2">
            {DEMO_ACCOUNTS.map((a) => (
              <button
                key={a.username}
                type="button"
                className="text-left text-sm rounded-lg border border-teal-100 bg-teal-50/80 px-3 py-2 hover:bg-teal-100 transition-colors"
                onClick={() => handleFillDemo(a.username)}
                aria-label={`填入演示账号 ${a.username}`}
              >
                <span className="font-medium text-teal-900 block">{a.label}</span>
                <span className="text-xs text-slate-500">{a.username}</span>
                <span className="block text-[11px] text-slate-400 mt-0.5">{a.hint}</span>
              </button>
            ))}
          </div>
        </div>
      </form>
    </div>
  );
}
