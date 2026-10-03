import 'package:flutter_test/flutter_test.dart';
import 'package:refreezer/api/definitions.dart';

Map song(String id, Map? rights) => {
      'SNG_ID': id,
      'SNG_TITLE': 'Song',
      'VERSION': '',
      'DURATION': '200',
      'ALB_PICTURE': 'x',
      'ALB_ID': '1',
      'ALB_TITLE': 'Album',
      'ART_ID': '1',
      'ART_NAME': 'Artist',
      'TRACK_NUMBER': '1',
      'DISK_NUMBER': '1',
      'EXPLICIT_LYRICS': '0',
      if (rights != null) 'RIGHTS': rights,
    };

void main() {
  test('track with streaming rights is available', () {
    var t = Track.fromPrivateJson(song('1', {'STREAM_ADS_AVAILABLE': true, 'STREAM_SUB_AVAILABLE': true}));
    expect(t.isUnavailable, false);
  });
  test('track with empty rights is unavailable', () {
    var t = Track.fromPrivateJson(song('2', {}));
    expect(t.isUnavailable, true);
  });
  test('missing rights field is treated as unknown (available)', () {
    var t = Track.fromPrivateJson(song('3', null));
    expect(t.available, null);
    expect(t.isUnavailable, false);
  });
}
