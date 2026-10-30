import rod from '../assets/breeze-rod.png'
import charge from '../assets/wind-charge-blue.png'
import chargePink from '../assets/wind-charge-pink.png'
import { describeError, useAction, useApp } from '../app/state'
import type { ParamsOf } from '../bridge/types'
import { RoleBadge } from '../ui/controls'
import { Icon } from '../ui/icons'

function PlayRow({ art, title, detail, onClick, testId }: {
  art: string
  title: string
  detail: string
  onClick: () => void
  testId: string
}) {
  return (
    <button className="play-row" onClick={onClick} data-testid={testId}>
      <img className="play-art" src={art} alt="" />
      <span className="play-text">
        <span className="play-title">{title}</span>
        <span className="play-detail">{detail}</span>
      </span>
      <Icon.Chevron className="play-go" />
    </button>
  )
}

export function Home() {
  const { hello, call, toast, navigate } = useApp()
  const game = useAction('game.state')
  const account = useAction('account.get')
  const modules = useAction('modules.list')
  const ingame = hello.context === 'ingame'

  const act = (action: 'game.singleplayer' | 'game.multiplayer' | 'ui.close' | 'game.pauseMenu' | 'hud.openEditor') =>
    call(action).catch((err) => toast(describeError(err), 'error'))

  const join = (params: ParamsOf<'game.joinServer'>) =>
    call('game.joinServer', params).catch((err) => toast(describeError(err), 'error'))

  const g = game.data
  const player = account.data?.playerName ?? g?.playerName ?? ''
  const enabledCount = modules.data?.filter((m) => m.enabled).length ?? null

  return (
    <div className="page home page-enter">
      <div className="home-id">
        <h1 className="home-name">{player || 'Breeze'}</h1>
        <div className="home-meta">
          <RoleBadge role={account.data?.role ?? null} />
          <span>Breeze {hello.modVersion} on Minecraft {hello.minecraftVersion}</span>
        </div>
      </div>

      {!ingame ? (
        <section className="play" aria-label="Play">
          <PlayRow testId="play-singleplayer" art={rod} title="Singleplayer" detail="Your worlds on this computer" onClick={() => act('game.singleplayer')} />
          <PlayRow testId="play-multiplayer" art={charge} title="Multiplayer" detail="Servers and LAN games" onClick={() => act('game.multiplayer')} />
          {g?.lastServer && (
            <PlayRow
              testId="play-last-server"
              art={chargePink}
              title={`Jump back in to ${g.lastServer.name}`}
              detail={g.lastServer.address}
              onClick={() => join({ address: g.lastServer!.address, name: g.lastServer!.name })}
            />
          )}
        </section>
      ) : (
        <section className="play" aria-label="Game">
          <div className="world-card">
            <div className="world-name">{g?.worldName ?? g?.serverName ?? (g ? 'In game' : '')}</div>
            <div className="world-detail">
              {g?.singleplayer ? 'Singleplayer world' : g?.serverAddress ?? ''}
              {g?.pingMs != null && <span className="mono"> {g.pingMs} ms</span>}
              {g && <span className="mono"> {g.fps} fps</span>}
            </div>
          </div>
          <div className="home-actions">
            <button className="btn btn-primary" onClick={() => act('ui.close')} data-testid="resume"><Icon.Play />Back to game</button>
            <button className="btn btn-secondary" onClick={() => act('game.pauseMenu')}>Game menu</button>
            <button className="btn btn-secondary" onClick={() => act('hud.openEditor')}><Icon.Layout />Edit HUD</button>
          </div>
        </section>
      )}

      <section className="home-links" aria-label="Shortcuts">
        <button className="home-link" onClick={() => navigate({ name: 'mods', tab: 'modules' })}>
          <Icon.Grid />
          <span>
            <span className="home-link-title">Mods</span>
            <span className="home-link-detail">{enabledCount == null ? 'Modules and installed mods' : `${enabledCount} modules on`}</span>
          </span>
        </button>
        {hello.features.cosmetics && (
          <button className="home-link" onClick={() => navigate({ name: 'wardrobe' })}>
            <Icon.Hanger />
            <span>
              <span className="home-link-title">Wardrobe</span>
              <span className="home-link-detail">Capes you own</span>
            </span>
          </button>
        )}
        <button className="home-link" onClick={() => navigate({ name: 'settings' })}>
          <Icon.Gear />
          <span>
            <span className="home-link-title">Settings</span>
            <span className="home-link-detail">Theme, scale and menu</span>
          </span>
        </button>
      </section>

      {account.data && account.data.breeze !== 'ready' && (
        <p className="home-note">
          {account.data.breeze === 'signing-in'
            ? 'Connecting to your Breeze account.'
            : account.data.breeze === 'unreachable'
              ? 'Breeze cannot be reached right now. Capes and friends come back when it can.'
              : 'Not signed in to Breeze. Start the game from the Breeze launcher to bring your capes and friends with you.'}
        </p>
      )}
    </div>
  )
}
