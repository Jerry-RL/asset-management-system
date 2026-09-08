import { createContext, useCallback, useContext, useMemo, useState } from 'react';
import { api, type LoginUser } from './api';

interface AuthState {
  token: string | null;
  user: LoginUser | null;
  login: (
    username: string,
    password: string,
    captchaId: string,
    captchaCode: string,
  ) => Promise<void>;
  logout: () => void;
}

const AuthContext = createContext<AuthState>({
  token: null,
  user: null,
  login: async () => {},
  logout: () => {},
});

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [token, setToken] = useState<string | null>(() => localStorage.getItem('h5worker.token'));
  const [user, setUser] = useState<LoginUser | null>(() => {
    const raw = localStorage.getItem('h5worker.user');
    return raw ? JSON.parse(raw) : null;
  });

  const login = useCallback(
    async (username: string, password: string, captchaId: string, captchaCode: string) => {
      const data = await api.post<{ accessToken: string; user: LoginUser }>('/auth/login', {
        username,
        password,
        captchaId,
        captchaCode,
      });
      localStorage.setItem('h5worker.token', data.accessToken);
      localStorage.setItem('h5worker.user', JSON.stringify(data.user));
      setToken(data.accessToken);
      setUser(data.user);
    },
    [],
  );

  const logout = useCallback(() => {
    localStorage.removeItem('h5worker.token');
    localStorage.removeItem('h5worker.user');
    setToken(null);
    setUser(null);
  }, []);

  const value = useMemo(() => ({ token, user, login, logout }), [token, user, login, logout]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  return useContext(AuthContext);
}
