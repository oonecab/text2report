import React from 'react';

interface BrandMarkProps {
  collapsed: boolean;
  icon: React.ReactNode;
  title: string;
  tone?: 'light' | 'dark';
}

export const BrandMark: React.FC<BrandMarkProps> = ({
  collapsed,
  icon,
  title,
  tone = 'light',
}) => (
  <div
    className={`brand-mark brand-mark-${tone} ${collapsed ? 'brand-mark-collapsed' : ''}`}
    aria-label={title}
  >
    <span className="brand-mark-icon">{icon}</span>
    <span className="brand-mark-title">{title}</span>
  </div>
);
