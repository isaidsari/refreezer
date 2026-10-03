import 'package:flutter_test/flutter_test.dart';
import 'package:refreezer/api/definitions.dart';

Map a(String id, String title, String type, int? explicit) => {
      'ALB_ID': id,
      'ALB_TITLE': title,
      'TYPE': type,
      'ALB_PICTURE': 'x',
      'ART_NAME': 'Artist',
      if (explicit != null)
        'EXPLICIT_ALBUM_CONTENT': {'EXPLICIT_LYRICS_STATUS': explicit, 'EXPLICIT_COVER_STATUS': 0},
    };

void main() {
  test('keeps clean version right after its explicit version', () {
    var artist = Artist.fromPrivateJson({'ART_ID': 1, 'ART_NAME': 'x'}, albumsJson: {
      'total': 4,
      'data': [a('1', 'Album', '1', 3), a('3', 'Other', '1', 0), a('2', 'Album', '1', 1), a('4', 'Album', '0', 3)]
    });
    expect(artist.albums.map((e) => e.id), ['3', '2', '1', '4']);
    expect(artist.albums.map((e) => e.isExplicit), [false, true, false, false]);
    expect(artist.albums.map((e) => e.isClean), [false, false, true, true]);
  });
  test('keeps clean-only album, skips duplicate ids', () {
    var list = Album.mergeUnique(<Album>[], [Album.fromPrivateJson(a('1', 'A', '1', 3))]);
    Album.mergeUnique(list, [Album.fromPrivateJson(a('1', 'A', '1', 3)), Album.fromPrivateJson(a('5', 'B', '1', null))]);
    expect(list.map((e) => e.id), ['1', '5']);
  });
  test('explicit from a later page groups earlier clean version', () {
    var list = Album.mergeUnique(<Album>[], [Album.fromPrivateJson(a('1', 'A ', '1', 3)), Album.fromPrivateJson(a('7', 'C', '1', 0))]);
    Album.mergeUnique(list, [Album.fromPrivateJson(a('2', 'a', '1', 4))]);
    expect(list.map((e) => e.id), ['7', '2', '1']);
  });
}
