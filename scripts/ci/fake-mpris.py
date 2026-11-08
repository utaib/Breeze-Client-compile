#!/usr/bin/env python3
"""A stand-in Spotify on the session bus, for the self-test only.

Publishes org.mpris.MediaPlayer2.spotify with one song, as the Spotify
desktop app does on Linux, so the game's local Spotify control
(common LocalSpotify, dbus-send) can be checked end to end: the game must
read the song and, when told to, pause it with PlayPause. Every call is
appended to the log file given as the first argument.

    python3 fake-mpris.py <log file>
"""
import sys

import dbus
import dbus.service
from dbus.mainloop.glib import DBusGMainLoop
from gi.repository import GLib

LOG = sys.argv[1] if len(sys.argv) > 1 else '/dev/null'
PLAYER = 'org.mpris.MediaPlayer2.Player'


def log(line):
    with open(LOG, 'a', encoding='utf-8') as f:
        f.write(line + '\n')


class Spotify(dbus.service.Object):
    def __init__(self, bus):
        super().__init__(bus, '/org/mpris/MediaPlayer2')
        self.playing = True
        self.track = 0

    def metadata(self):
        return dbus.Dictionary({
            'mpris:trackid': dbus.ObjectPath('/com/spotify/track/0VjIjW4GlUZAMYd2vXMi3b'),
            'mpris:length': dbus.UInt64(201000000),
            'mpris:artUrl': 'https://i.scdn.co/image/ab67616d0000b2730000000000000000000000ff',
            'xesam:title': 'Local Test Song %d' % (self.track + 1),
            'xesam:album': 'Breeze CI',
            'xesam:artist': dbus.Array(['The Local Testers'], signature='s'),
        }, signature='sv')

    @dbus.service.method(dbus.PROPERTIES_IFACE, in_signature='ss', out_signature='v')
    def Get(self, interface, prop):
        log('Get %s' % prop)
        if prop == 'PlaybackStatus':
            return 'Playing' if self.playing else 'Paused'
        if prop == 'Metadata':
            return self.metadata()
        if prop == 'Position':
            return dbus.Int64(42000000)
        raise dbus.exceptions.DBusException('org.freedesktop.DBus.Error.InvalidArgs', 'no property ' + prop)

    @dbus.service.method(PLAYER)
    def PlayPause(self):
        self.playing = not self.playing
        log('PlayPause -> %s' % ('Playing' if self.playing else 'Paused'))

    @dbus.service.method(PLAYER)
    def Next(self):
        self.track += 1
        log('Next')

    @dbus.service.method(PLAYER)
    def Previous(self):
        self.track = max(0, self.track - 1)
        log('Previous')


DBusGMainLoop(set_as_default=True)
bus = dbus.SessionBus()
name = dbus.service.BusName('org.mpris.MediaPlayer2.spotify', bus)
Spotify(bus)
log('ready')
GLib.MainLoop().run()
