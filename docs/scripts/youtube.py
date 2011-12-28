import re, os
import binascii
import string
import gdata.youtube
import gdata.youtube.service
from gdata.media import YOUTUBE_NAMESPACE
import gdata.tlslite.utils.dateFuncs
import time

CATEGORIES_SCHEME = "http://gdata.youtube.com/schemas/2007/categories.cat"

class YouTube(object):
    DEVELOPER_KEY = 'AI39si497iB2cNY-lw-KNCyjL_GMpw_3x9HO7Rro9_KksfUvyvnlm9fR-gR3ASCcbGF7zHqFPJQAGiJLni0R3AL8sXdHDMaFvw'
    PASSWORD = 'tudOoradNed3'
    EMAIL='youtube@defold.se'
    USERID='defoldvideos'
    EXTENSIONS = '.ivf'.split()

    def __init__(self):
        self.title_to_video = {}
        self.sign_in()
        self.fetch_uploaded_videos()
        self.print_uploaded_videos()

    def sign_in(self):
        self.service = gdata.youtube.service.YouTubeService()
        self.service.ssl = False
        self.service.developer_key = YouTube.DEVELOPER_KEY
        self.service.email = YouTube.EMAIL
        self.service.password = YouTube.PASSWORD
        self.service.ProgrammaticLogin()

    def print_uploaded_videos(self):
        print "Uploaded Videos:"
        for entry in self.all_entries:
            print '%s (%s)' % (entry.media.title.text, entry.media.keywords.text)
            if entry.control:
                for i, e in enumerate(entry.control.extension_elements):
                    if e.tag == 'state':
                        print 'state: %s' % (e.attributes['name'])
            print ""
        if len(self.all_entries) == 0:
            print 'No videos uploaded'

    def fetch_uploaded_videos(self):
        uri = 'http://gdata.youtube.com/feeds/api/users/%s/uploads' % YouTube.USERID
        feed = self.service.GetYouTubePlaylistVideoFeed(uri)
        for entry in feed.entry:
            title = entry.media.title.text
            lst = self.title_to_video.get(title, [])
            lst.append(entry)
            self.title_to_video[title] = lst

        self.all_entries = feed.entry

    def _filename_to_title(self, file_name):
        title =  os.path.splitext(os.path.basename(file_name))[0]
        title_lst = title.split('_')
        title_lst = map(lambda x: x[0].upper() + x[1:], title_lst)
        title_nice = ' '.join(title_lst)
        return title_nice

    def sync(self, top):
        print "Synchronizing:"
        videos = self.find_upload_candidates(top)
        for file_name in videos:
            title = self._filename_to_title(file_name)
            f = open(file_name, 'rb')
            crc = binascii.crc32(f.read()) & 0xffffffff
            crc = '%x' % crc
            f.close()

            entry = self.find_video(title, crc)
            if entry:
                print 'Skipping "%s". Already uploaded (crc=%s)' % (file_name, crc)
            else:
                print 'Uploading "%s" (crc=%s)' % (file_name, crc)
                self.upload(file_name, crc)

    def _create_video_entry(self, title, description, category, keywords=None,
                            location=None, private=False, unlisted=False):
        media_group = gdata.media.Group(
            title=gdata.media.Title(text=title),
            description=gdata.media.Description(description_type='plain', text=description),
            keywords=gdata.media.Keywords(text=keywords),
            category=gdata.media.Category(
                text=category,
                label=category,
                scheme=CATEGORIES_SCHEME),
            private=(gdata.media.Private() if private else None),
            player=None)
        if location:
            where = gdata.geo.Where()
            where.set_location(location)
        else:
            where = None
        kwargs = {
          "namespace": YOUTUBE_NAMESPACE,
          "attributes": {'action': 'list', 'permission': 'denied'},
        }
        extension = ([ExtensionElement('accessControl', **kwargs)] if unlisted else None)
        return gdata.youtube.YouTubeVideoEntry(media=media_group, geo=where, extension_elements=extension)

    def upload(self, file_name, crc):
        title = self._filename_to_title(file_name)
        entry = self._create_video_entry(title, title, "Games", keywords = 'crc=%s' % crc)
        entry2 = self.service.InsertVideoEntry(entry, file_name)

    def find_video(self, title, crc):
        for entry in self.title_to_video.get(title, []):
            tags = entry.media.keywords.text.split(',')
            tags = map(string.strip, tags)
            for t in tags:
                m = re.match('crc=(.*)', t)
                if m:
                    if m.groups()[0] == crc:
                        return entry

        return None

    def find_upload_candidates(self, top):
        ret = []
        for root, dirs, files in os.walk(top):
            for f in files:
                tmp, ext = os.path.splitext(f)
                if ext in YouTube.EXTENSIONS:
                    ret.append(os.path.join(root, f))
        return ret
