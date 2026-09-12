import { Button, Result } from 'antd';
import { useNavigate } from 'react-router-dom';

/**
 * 403 提示（设计 6.2 的路由守卫落点）。
 *
 * <p>刻意**不**做成整页跳转：它渲染在 `AdminLayout` 的内容区里，侧边栏与页签保留，
 * 用户可以直接改点别的菜单。整页 403 会把导航一起藏掉，把人困在一个只能后退的界面上。
 *
 * <p>文案给出 `menuCode` 而不是「无权限」三个字：管理员据此能直接定位到
 * 角色权限页里该勾哪一行，否则反馈只能是「我进不去」。
 */
export function ForbiddenNotice({ menuCode }: { menuCode: string }) {
  const navigate = useNavigate();
  return (
    <Result
      status="403"
      title="403"
      subTitle={
        <span>
          当前账号没有该页面的访问权限
          <span className="ml-1 text-[var(--ams-text-secondary)]">（{menuCode}:view）</span>
        </span>
      }
      extra={
        <Button type="primary" onClick={() => navigate('/')}>
          返回首页
        </Button>
      }
    />
  );
}
