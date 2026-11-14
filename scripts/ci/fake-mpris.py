#!/usr/bin/env python3
"""A stand-in music player on the session bus, for the self-test only.

Publishes org.mpris.MediaPlayer2.chromium.instance4242 with one song and a
cover file, as a Chromium browser playing YouTube Music does on Linux, so the
game's reading of any player on the computer (common LocalMedia, dbus-send)
can be checked end to end: the game must find the player, read the song and
its position and, when told to, pause it with PlayPause. Every call is
appended to the log file given as the first argument.

    python3 fake-mpris.py <log file>
"""
import base64
import os
import sys

import dbus
import dbus.service
from dbus.mainloop.glib import DBusGMainLoop
from gi.repository import GLib

LOG = sys.argv[1] if len(sys.argv) > 1 else '/dev/null'
PLAYER = 'org.mpris.MediaPlayer2.Player'
ROOT = 'org.mpris.MediaPlayer2'
NAME = 'org.mpris.MediaPlayer2.chromium.instance4242'

# A browser writes the cover to a file and gives its address: an 8x8 PNG here.
COVER = os.path.join(os.path.dirname(os.path.abspath(LOG)), 'fake-cover.png')
with open(COVER, 'wb') as f:
    f.write(base64.b64decode(
        'iVBORw0KGgoAAAANSUhEUgAAAAgAAAAICAIAAABLbSncAAAAEUlEQVR4nGOQu56AFTEMLQkAPzdVQYPD8Z0AAAAASUVORK5CYII='))


def log(line):
    with open(LOG, 'a', encoding='utf-8') as f:
        f.write(line + '\n')


class Browser(dbus.service.Object):
    def __init__(self, bus):
        super().__init__(bus, '/org/mpris/MediaPlayer2')
        self.playing = True
        self.track = 0

    def metadata(self):
        return dbus.Dictionary({
            'mpris:trackid': dbus.ObjectPath('/org/chromium/MediaPlayer2/TrackList/Track%d' % self.track),
            'mpris:length': dbus.Int64(201000000),
            'mpris:artUrl': 'file://' + COVER,
            'xesam:title': 'Local Test Song %d' % (self.track + 1),
            'xesam:album': 'Breeze CI',
            'xesam:artist': dbus.Array(['The Local Testers'], signature='s'),
        }, signature='sv')

    @dbus.service.method(dbus.PROPERTIES_IFACE, in_signature='ss', out_signature='v')
    def Get(self, interface, prop):
        log('Get %s' % prop)
        if interface == ROOT and prop == 'Identity':
            return 'Chromium'
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
name = dbus.service.BusName(NAME, bus)
Browser(bus)
log('ready')
GLib.MainLoop().run()
