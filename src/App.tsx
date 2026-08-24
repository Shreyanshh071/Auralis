import React, { useState, useEffect } from 'react';
import { AuthProvider } from './context/AuthContext';
import { PlayerProvider, usePlayer } from './context/PlayerContext';
import { ListenTogetherProvider, useListenTogether } from './context/ListenTogetherContext';
import { Sidebar } from './components/common/Sidebar';
import { Header } from './components/common/Header';
import { HomeView } from './components/views/HomeView';
import { ExploreView } from './components/views/ExploreView';
import { LibraryView } from './components/views/LibraryView';
import { FavoritesView } from './components/views/FavoritesView';
import { MiniPlayer } from './components/player/MiniPlayer';
import { NowPlayingModal } from './components/player/NowPlayingModal';
import { MobileNav } from './components/common/MobileNav';
import { CreatePlaylistModal } from './components/modals/CreatePlaylistModal';
import { ListenTogetherModal } from './components/modals/ListenTogetherModal';
import { LegalModal, type LegalTab } from './components/modals/LegalModal';
import { AccountModal } from './components/modals/AccountModal';
import { ToastContainer } from './components/common/Toast';
import { extractRoomCodeFromUrl } from './lib/listenTogether';
import type { Track } from './types/music';

const AppContent: React.FC = () => {
  const [activeView, setActiveView] = useState<string>('home');
  // The query Explore should run, plus a nonce so submitting the *same* query
  // again still re-triggers the search instead of being ignored as unchanged.
  const [exploreRequest, setExploreRequest] = useState<{ query: string; nonce: number }>({
    query: '',
    nonce: 0,
  });
  const [isCreatePlaylistOpen, setIsCreatePlaylistOpen] = useState<boolean>(false);
  const [isLegalOpen, setIsLegalOpen] = useState<boolean>(false);
  const [legalTab, setLegalTab] = useState<LegalTab>('privacy');
  const [isAccountOpen, setIsAccountOpen] = useState<boolean>(false);
  // A playlist the Library should open as soon as it mounts, set when one is
  // clicked in the sidebar. Cleared by the Library once consumed, so switching
  // away and back does not reopen it.
  const [pendingPlaylistId, setPendingPlaylistId] = useState<string | undefined>(undefined);
  const { currentTrack } = usePlayer();
  const { setInviteCodeToOpen, setIsModalOpen } = useListenTogether();

  // Check URL on startup for /privacy or /terms direct routes & ?room=CODE invite links
  useEffect(() => {
    const path = window.location.pathname.toLowerCase();
    if (path === '/privacy' || path === '/privacy-policy') {
      setLegalTab('privacy');
      setIsLegalOpen(true);
    } else if (path === '/terms' || path === '/terms-of-service') {
      setLegalTab('terms');
      setIsLegalOpen(true);
    }

    const code = extractRoomCodeFromUrl(window.location.href);
    if (code) {
      setInviteCodeToOpen(code);
      setIsModalOpen(true);
      // Clean up room code from query params so page refresh is clean
      try {
        const url = new URL(window.location.href);
        url.searchParams.delete('room');
        window.history.replaceState({}, document.title, url.pathname + url.search + url.hash);
      } catch {}
    }
  }, [setInviteCodeToOpen, setIsModalOpen]);

  /** Send a query to Explore and switch to it. Used by genre tiles and header search.
   *  An empty query signals "clear" and resets the Explore view to its history state. */
  const handleSelectGenre = (genreQuery: string) => {
    setExploreRequest((prev) => ({ query: genreQuery, nonce: prev.nonce + 1 }));
    if (genreQuery) {
      setActiveView('explore');
    }
    // When the query is empty (search cleared), we update the request so
    // ExploreView returns to its history-only empty state, but we don't
    // force-navigate — the user may not even be on the explore page.
  };

  /** Open a specific playlist. The sidebar previously just switched to the
   *  Library and dropped the id, so every playlist link led to the same grid. */
  const handleOpenPlaylist = (playlistId: string) => {
    setPendingPlaylistId(playlistId);
    setActiveView('library');
  };

  const handleSearchSelect = (_track: Track) => {
    // Song is played automatically by Header
  };

  return (
    <div
      /* Drives the --player-h step of the bottom stacking system in index.css,
       * so the FAB, toasts and scroll padding all collapse by one slot when
       * nothing is playing and the MiniPlayer renders nothing. */
      data-has-player={currentTrack ? 'true' : 'false'}
      className="relative flex h-[100dvh] w-screen bg-[var(--bg-base)] text-[var(--text-primary)] overflow-hidden font-sans transition-colors duration-200"
    >
      {/*
        Material 3 surface tint: the artwork-derived accent laid over the neutral
        surface at a very low alpha, strongest at the top of the shell.

        This replaces two 55vw/45vw circles that were blurred by 140-160px and
        painted with the raw artwork colour — which is what produced the purple
        wash across the whole app whenever a cover could not be sampled (the
        extractor's failure colour used to be violet). A flat gradient also costs
        nothing to raster, where a blurred element that large is one of the most
        expensive things a mobile WebView can be asked to composite.
      */}
      <div
        aria-hidden="true"
        className="absolute inset-0 pointer-events-none bg-gradient-to-b from-[var(--m3-surface-tint)] via-transparent to-transparent transition-colors duration-700"
      />

      {/* Desktop Sidebar (Hidden on mobile) */}
      <Sidebar
        activeView={activeView}
        setActiveView={setActiveView}
        openCreatePlaylistModal={() => setIsCreatePlaylistOpen(true)}
        openPlaylist={handleOpenPlaylist}
        onOpenLegal={(tab) => {
          setLegalTab(tab);
          setIsLegalOpen(true);
        }}
      />

      {/* Main Content Area */}
      <div className="flex-1 flex flex-col min-w-0 h-full overflow-hidden relative z-10">
        <Header
          activeView={activeView}
          setActiveView={setActiveView}
          onSearchSelect={handleSearchSelect}
          onSubmitSearch={handleSelectGenre}
          onOpenAccount={() => setIsAccountOpen(true)}
        />

        <main className="flex-1 overflow-y-auto px-4 sm:px-8 py-4 pb-[var(--content-bottom)]">
          {activeView === 'home' && (
            <HomeView onSelectGenre={handleSelectGenre} setActiveView={setActiveView} />
          )}

          {activeView === 'explore' && (
            <ExploreView
              initialQuery={exploreRequest.query}
              queryNonce={exploreRequest.nonce}
            />
          )}

          {activeView === 'library' && (
            <LibraryView
              openCreatePlaylistModal={() => setIsCreatePlaylistOpen(true)}
              openPlaylistId={pendingPlaylistId}
              onPlaylistOpened={() => setPendingPlaylistId(undefined)}
              onSelectArtist={handleSelectGenre}
            />
          )}

          {activeView === 'favorites' && <FavoritesView />}
        </main>

        {/* Floating Mini Player */}
        <MiniPlayer />

        {/* Mobile Bottom Navigation Bar (Hidden on desktop) */}
        <MobileNav activeView={activeView} setActiveView={setActiveView} />
      </div>

      {/* Fullscreen Now Playing Modal */}
      <NowPlayingModal />

      {/* Create Playlist Modal */}
      <CreatePlaylistModal
        isOpen={isCreatePlaylistOpen}
        onClose={() => setIsCreatePlaylistOpen(false)}
      />

      {/* Listen Together Modal */}
      <ListenTogetherModal />

      {/* Privacy Policy & Terms of Service Modal */}
      <LegalModal
        isOpen={isLegalOpen}
        initialTab={legalTab}
        onClose={() => setIsLegalOpen(false)}
      />

      {/* Account / Profile Modal — sign-in, cloud sync, playlist import, settings */}
      <AccountModal
        isOpen={isAccountOpen}
        onClose={() => setIsAccountOpen(false)}
        onOpenLegal={(tab) => {
          setLegalTab(tab);
          setIsLegalOpen(true);
        }}
      />

      {/* Toast Notifications */}
      <ToastContainer />
    </div>
  );
};

export default function App() {
  return (
    <AuthProvider>
      <PlayerProvider>
        <ListenTogetherProvider>
          <AppContent />
        </ListenTogetherProvider>
      </PlayerProvider>
    </AuthProvider>
  );
}
