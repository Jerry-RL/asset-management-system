import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '@/lib/auth';

export function LoginPage() {
  const { login } = useAuth();
  const navigate = useNavigate();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    setLoading(true);
    try {
      await login(username, password);
      navigate('/');
    } catch (err) {
      setError(err instanceof Error ? err.message : '登录失败');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="min-h-screen flex flex-col items-center justify-center bg-gradient-to-b from-blue-500 to-blue-700 px-8">
      <div className="text-4xl font-bold text-white mb-2">资管云</div>
      <div className="text-white/80 mb-10 text-sm">资产经营管理 · 用户端</div>
      <form onSubmit={submit} className="w-full max-w-sm bg-white rounded-xl p-6 space-y-4">
        <input
          className="w-full border rounded-lg px-4 py-3"
          placeholder="账号"
          value={username}
          onChange={(e) => setUsername(e.target.value)}
        />
        <input
          className="w-full border rounded-lg px-4 py-3"
          type="password"
          placeholder="密码"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
        />
        {error && <p className="text-red-500 text-sm">{error}</p>}
        <button
          className="w-full bg-blue-600 text-white rounded-lg py-3 disabled:opacity-50"
          disabled={loading}
        >
          {loading ? '登录中...' : '登录'}
        </button>
      </form>
    </div>
  );
}
