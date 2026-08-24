import React, { useState } from 'react';
import { X, ShieldCheck, FileText } from 'lucide-react';

export type LegalTab = 'privacy' | 'terms';

interface LegalModalProps {
  isOpen: boolean;
  initialTab?: LegalTab;
  onClose: () => void;
}

export const LegalModal: React.FC<LegalModalProps> = ({
  isOpen,
  initialTab = 'privacy',
  onClose,
}) => {
  const [activeTab, setActiveTab] = useState<LegalTab>(initialTab);

  // Sync activeTab when initialTab changes on open
  React.useEffect(() => {
    if (isOpen) {
      setActiveTab(initialTab);
    }
  }, [isOpen, initialTab]);

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/80 backdrop-blur-md animate-in fade-in">
      <div className="relative w-full max-w-2xl max-h-[85vh] flex flex-col rounded-3xl bg-[var(--bg-popover)] border border-[var(--border-medium)] shadow-2xl text-[var(--text-primary)] overflow-hidden">
        {/* Header */}
        <div className="flex items-center justify-between p-6 border-b border-[var(--border-subtle)]">
          <div className="flex items-center gap-3">
            <div className="p-2.5 rounded-2xl bg-[var(--m3-secondary-container)] text-[var(--m3-on-secondary-container)] border border-[var(--m3-outline-variant)]">
              {activeTab === 'privacy' ? (
                <ShieldCheck className="w-5 h-5" />
              ) : (
                <FileText className="w-5 h-5" />
              )}
            </div>
            <div>
              <h3 className="font-display font-black text-lg text-[var(--text-primary)]">
                {activeTab === 'privacy' ? 'Privacy Policy' : 'Terms of Service'}
              </h3>
              <p className="text-xs text-[var(--text-muted)]">Auralis Music Application</p>
            </div>
          </div>
          <button
            onClick={onClose}
            className="p-1.5 rounded-full hover:bg-[var(--bg-surface-hover)] text-[var(--text-muted)] hover:text-[var(--text-primary)] transition cursor-pointer"
            aria-label="Close modal"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        {/* Tab Switcher */}
        <div className="flex px-6 pt-4 gap-2 border-b border-[var(--border-subtle)] bg-[var(--bg-surface)]">
          <button
            onClick={() => setActiveTab('privacy')}
            className={`pb-3 px-4 text-xs font-bold transition-all border-b-2 cursor-pointer ${
              activeTab === 'privacy'
                ? 'border-[var(--m3-primary)] text-[var(--m3-primary)]'
                : 'border-transparent text-[var(--text-muted)] hover:text-[var(--text-primary)]'
            }`}
          >
            Privacy Policy
          </button>
          <button
            onClick={() => setActiveTab('terms')}
            className={`pb-3 px-4 text-xs font-bold transition-all border-b-2 cursor-pointer ${
              activeTab === 'terms'
                ? 'border-[var(--m3-primary)] text-[var(--m3-primary)]'
                : 'border-transparent text-[var(--text-muted)] hover:text-[var(--text-primary)]'
            }`}
          >
            Terms of Service
          </button>
        </div>

        {/* Content Body */}
        <div className="flex-1 overflow-y-auto p-6 space-y-6 text-sm text-[var(--text-secondary)] leading-relaxed">
          {activeTab === 'privacy' ? (
            <div className="space-y-4">
              <div>
                <p className="text-xs text-[var(--text-muted)]">Last updated: August 2026</p>
              </div>

              <section className="space-y-2">
                <h4 className="font-bold text-sm text-[var(--text-primary)]">1. Overview</h4>
                <p>
                  Auralis is an open-source, client-focused music player application designed to provide a privacy-respecting music streaming experience. We prioritize user privacy, data minimization, and transparent control over your information.
                </p>
              </section>

              <section className="space-y-2">
                <h4 className="font-bold text-sm text-[var(--text-primary)]">2. Information We Access and How It Is Used</h4>
                <p>
                  <strong>YouTube and Google User Data:</strong> When you connect your Google Account to import playlists, Auralis requests read-only access (<code className="text-xs px-1.5 py-0.5 rounded bg-[var(--bg-surface-elevated)] font-mono">youtube.readonly</code>) to retrieve your personal playlist metadata (playlist titles, track titles, artists, and thumbnails).
                </p>
                <p>
                  <strong>No Data Selling or Advertising:</strong> We do not sell, rent, monetize, or transfer your personal data or Google user data to any third parties, data brokers, or advertisers.
                </p>
                <p>
                  <strong>Limited Use Policy Compliance:</strong> Auralis adheres strictly to the{' '}
                  <a
                    href="https://developers.google.com/terms/api-services-user-data-policy"
                    target="_blank"
                    rel="noreferrer"
                    className="text-[var(--m3-primary)] hover:underline"
                  >
                    Google API Services User Data Policy
                  </a>
                  , including the Limited Use requirements.
                </p>
              </section>

              <section className="space-y-2">
                <h4 className="font-bold text-sm text-[var(--text-primary)]">3. Local-First Storage & Cloud Sync</h4>
                <p>
                  By default, your favorites, listening history, and custom playlists are stored locally on your device using IndexedDB and localStorage. If you choose to enable cloud synchronization via Firebase Authentication, your encrypted library preferences and playlist references are safely synced to your private account in Google Firebase/Firestore.
                </p>
              </section>

              <section className="space-y-2">
                <h4 className="font-bold text-sm text-[var(--text-primary)]">4. Data Retention and Deletion</h4>
                <p>
                  You can clear your local cache, history, or disconnect your Google/YouTube account at any time directly through the app settings or by clearing your browser data. Upon disconnecting, access tokens are immediately discarded from your device.
                </p>
              </section>

              <section className="space-y-2">
                <h4 className="font-bold text-sm text-[var(--text-primary)]">5. Contact Information</h4>
                <p>
                  If you have any questions or concerns regarding this Privacy Policy, you may contact the developer at{' '}
                  <a
                    href="mailto:rickyindian6@gmail.com"
                    className="text-[var(--m3-primary)] hover:underline"
                  >
                    rickyindian6@gmail.com
                  </a>
                  .
                </p>
              </section>
            </div>
          ) : (
            <div className="space-y-4">
              <div>
                <p className="text-xs text-[var(--text-muted)]">Last updated: August 2026</p>
              </div>

              <section className="space-y-2">
                <h4 className="font-bold text-sm text-[var(--text-primary)]">1. Acceptance of Terms</h4>
                <p>
                  By accessing or using the Auralis web application or mobile client, you agree to be bound by these Terms of Service. If you do not agree to these terms, please do not use the application.
                </p>
              </section>

              <section className="space-y-2">
                <h4 className="font-bold text-sm text-[var(--text-primary)]">2. Description of Service</h4>
                <p>
                  Auralis is a personal audio client interface that facilitates playback of online audio, playlist organization, synchronization, and synchronized room listening. Auralis does not host copyrighted media files directly on its servers.
                </p>
              </section>

              <section className="space-y-2">
                <h4 className="font-bold text-sm text-[var(--text-primary)]">3. Third-Party Services</h4>
                <p>
                  Auralis may integrate with third-party APIs (such as YouTube API Services). By utilizing YouTube playlist import features, you also agree to be bound by the{' '}
                  <a
                    href="https://www.youtube.com/t/terms"
                    target="_blank"
                    rel="noreferrer"
                    className="text-[var(--m3-primary)] hover:underline"
                  >
                    YouTube Terms of Service
                  </a>{' '}
                  and the{' '}
                  <a
                    href="https://policies.google.com/privacy"
                    target="_blank"
                    rel="noreferrer"
                    className="text-[var(--m3-primary)] hover:underline"
                  >
                    Google Privacy Policy
                  </a>
                  .
                </p>
              </section>

              <section className="space-y-2">
                <h4 className="font-bold text-sm text-[var(--text-primary)]">4. Acceptable Use</h4>
                <p>
                  You agree to use Auralis only for lawful, personal, non-commercial purposes. You agree not to attempt to disrupt, compromise, or reverse-engineer the service infrastructure or misuse public API endpoints.
                </p>
              </section>

              <section className="space-y-2">
                <h4 className="font-bold text-sm text-[var(--text-primary)]">5. Disclaimer of Warranties</h4>
                <p>
                  Auralis is provided on an &quot;AS IS&quot; and &quot;AS AVAILABLE&quot; basis without warranties of any kind, whether express or implied.
                </p>
              </section>

              <section className="space-y-2">
                <h4 className="font-bold text-sm text-[var(--text-primary)]">6. Contact</h4>
                <p>
                  For any inquiries regarding these terms, please reach out to{' '}
                  <a
                    href="mailto:rickyindian6@gmail.com"
                    className="text-[var(--m3-primary)] hover:underline"
                  >
                    rickyindian6@gmail.com
                  </a>
                  .
                </p>
              </section>
            </div>
          )}
        </div>

        {/* Footer */}
        <div className="p-4 border-t border-[var(--border-subtle)] bg-[var(--bg-surface-elevated)] flex justify-end">
          <button
            onClick={onClose}
            className="px-6 py-2 rounded-full bg-[var(--m3-primary)] hover:bg-[var(--m3-primary-hover)] text-xs font-bold text-[var(--m3-on-primary)] transition cursor-pointer shadow-sm"
          >
            Close
          </button>
        </div>
      </div>
    </div>
  );
};
