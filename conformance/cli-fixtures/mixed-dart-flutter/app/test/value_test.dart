import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('value', () {
    File('mixed-flutter.marker').writeAsStringSync('ran\n');
    expect(1, 1);
  });
}
