"""Offline tests, run with: python3 -B -m unittest discover -s pocket_bridge -v"""
from contextlib import redirect_stdout
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import pocket_bridge as bridge


class WorkspaceTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name, 'work')
        self.work = bridge.Workspace(self.root)
        self.addCleanup(self.work.close)

    def run_action(self, action, path, **kwargs):
        draft = json.dumps(dict(action=action, path=path, **kwargs))
        return self.work.execute(bridge.parse_action(draft))

    def test_create_read_list_count(self):
        self.run_action('mkdir', 'notes')
        text = 'Namaste bhai!\nकल\n'
        self.run_action('write', 'notes/hello world', text=text)
        self.assertEqual(self.run_action('read', 'notes/hello world'), text)
        self.assertEqual(json.loads(self.run_action('ls', '.')), ['notes/'])
        self.assertEqual(json.loads(self.run_action('ls', 'notes')), ['hello world'])
        self.assertEqual(self.run_action('wc', 'notes/hello world'),
                         f'notes/hello world: 2 lines, 3 words, {len(text.encode())} bytes')

    def test_existing_file_and_missing_parent(self):
        self.run_action('write', 'todo', text='original')
        with self.assertRaises(FileExistsError):
            self.run_action('write', 'todo', text='replacement')
        self.assertEqual((self.root / 'todo').read_text(), 'original')
        with self.assertRaises(FileNotFoundError):
            self.run_action('write', 'missing/todo', text='new')

    def test_links_never_followed(self):
        outside = self.root.parent / 'other'
        outside.mkdir()
        (outside / 'file').write_text('private')
        (self.root / 'linked-dir').symlink_to(outside, target_is_directory=True)
        (self.root / 'linked-file').symlink_to(outside / 'file')
        with self.assertRaises(OSError):
            self.run_action('write', 'linked-dir/todo', text='new')
        with self.assertRaises(OSError):
            self.run_action('read', 'linked-file')
        self.assertFalse((outside / 'todo').exists())
        os.link(outside / 'file', self.root / 'hardlink')
        with self.assertRaises(bridge.RequestError):
            self.run_action('read', 'hardlink')

    def test_file_and_listing_limits(self):
        (self.root / 'large').write_bytes(b'x' * (bridge.FILE_LIMIT + 1))
        (self.root / 'binary').write_bytes(b'ab\0cd')
        os.mkfifo(self.root / 'pipe')
        for path in ['large', 'binary', 'pipe']:
            with self.subTest(path=path), self.assertRaises(bridge.RequestError):
                self.run_action('read', path)
        (self.root / '.hidden').touch()
        self.assertNotIn('.hidden', json.loads(self.run_action('ls', '.')))
        with patch.object(bridge, 'ENTRY_LIMIT', 1):
            self.assertIn('truncated', self.run_action('ls', '.'))

    def test_cancel_does_not_create_or_read(self):
        with patch('builtins.input', return_value=''), redirect_stdout(io.StringIO()):
            self.assertIsNone(bridge.review(self.work, '{"action":"mkdir","path":"notes"}'))
            self.assertIsNone(bridge.review(self.work, '{"action":"read","path":"absent"}'))
        self.assertFalse((self.root / 'notes').exists())

    def test_approved_menu_session(self):
        answers = ['3', '```json', '{"action":"mkdir","path":"notes"}', '```', 'end-beef',
                   'RUN', '4', 'q']
        out = io.StringIO()
        with patch('sys.argv', ['bridge', '--workspace', str(self.root)]), \
                patch('sys.stdin.isatty', return_value=True), \
                patch('secrets.token_hex', return_value='beef'), \
                patch('builtins.input', side_effect=answers), \
                patch.object(bridge, 'trusted_bin', return_value=None), redirect_stdout(out):
            self.assertEqual(bridge.main(), 0)
        self.assertTrue((self.root / 'notes').is_dir())
        self.assertIn('Local result (mkdir notes)', out.getvalue())
        self.assertIn('Nothing was sent', out.getvalue())


class HandoffTests(unittest.TestCase):
    def test_app_launch_failure_falls_back_to_fixed_chat_url(self):
        with patch.object(bridge, 'trusted_bin', side_effect=['/bin/am', '/bin/termux-open-url']), \
                patch('os.path.isdir', return_value=True), redirect_stdout(io.StringIO()), \
                patch('subprocess.run', side_effect=[PermissionError(), None]) as run:
            bridge.open_chat()
        self.assertEqual(run.call_args_list[0].args[0][-1], 'com.deepseek.chat')
        self.assertEqual(run.call_args_list[1].args[0], ['/bin/termux-open-url', bridge.CHAT_URL])

    def test_copy_opt_in_and_manual_fallback(self):
        out = io.StringIO()
        with patch.object(bridge, 'trusted_bin', return_value='/bin/termux-clipboard-set'), \
                patch('builtins.input', side_effect=['', 'y']), redirect_stdout(out), \
                patch('subprocess.run', side_effect=OSError('unavailable')) as run:
            bridge.show_and_copy('local result')
            run.assert_not_called()
            bridge.show_and_copy('local result')
            self.assertEqual(run.call_args.kwargs['input'], b'local result')
        self.assertIn('long-press', out.getvalue())


class InputTests(unittest.TestCase):
    def test_reject_unrecognised_or_ambiguous_drafts(self):
        for draft in ['ls', '[]', '{}', '{"action":"shell","path":"a"}',
                      '{"action":"ls","path":".","extra":true}',
                      '{"action":"ls","path":".","path":"folder"}',
                      '{"action":"write","path":"notes"}',
                      '{"action":"ls","path":"."}\nRUN']:
            with self.subTest(draft=draft), self.assertRaises(bridge.RequestError):
                bridge.parse_action(draft)

    def test_invalid_paths_and_text(self):
        for path in ['/abs', 'a/../b', '.env', 'a/.git/file', 'a//b', 'a\nb', '.', '', None]:
            with self.subTest(path=path), self.assertRaises(bridge.RequestError):
                bridge.parse_action(json.dumps({'action': 'read', 'path': path}))
        with self.assertRaises(bridge.RequestError):
            bridge.parse_action(json.dumps({'action': 'write', 'path': 'a', 'text': 'क' * 6000}))

    def test_paste_is_data_not_approval(self):
        with patch('secrets.token_hex', return_value='beef'), redirect_stdout(io.StringIO()), \
                patch('builtins.input', side_effect=['{"action":"ls","path":"."}', 'RUN', 'q', 'end-beef']):
            draft = bridge.pasted_draft()
        with self.assertRaises(bridge.RequestError):
            bridge.parse_action(draft)

    def test_terminal_control_characters_escaped(self):
        self.assertEqual(bridge.shown('a\x1bb\r\n'), 'a\\x1bb\\r\n')


if __name__ == '__main__':
    unittest.main()
