import 'dart:io';

import 'package:test/test.dart';

void main() {
  test('value', () {
    File('mixed-dart.marker').writeAsStringSync('ran\n');
    expect(1, 1);
  });
}
