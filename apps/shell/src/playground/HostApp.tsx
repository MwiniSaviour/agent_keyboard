'use client';
/** Renders the simulated host app (MockHost) — what would be WhatsApp / Chrome / Keep on a device. */
import { useEffect, useRef } from 'react';
import type { EditorState } from '@ak/protocol';
import type { MockHost } from '@/bridge/web-mock/host';
import { useStore } from '@/lib/store';
import { ChevronLeft, LockIcon, SendIcon } from '@/ui/icons';
import css from './playground.module.css';

function FieldText({ editor, placeholder, focused, secret }: { editor: EditorState; placeholder: string; focused: boolean; secret?: boolean }) {
  const mask = (s: string) => (secret ? '•'.repeat([...s].length) : s);
  const empty = !editor.before && !editor.after && !editor.selected;
  return (
    <span className={css.fieldText}>
      {empty && !focused ? <span className={css.placeholder}>{placeholder}</span> : null}
      {mask(editor.before)}
      {editor.selected ? <mark className={css.sel}>{mask(editor.selected)}</mark> : focused ? <i className={css.caret} aria-hidden /> : null}
      {mask(editor.after)}
      {empty && focused ? <span className={css.placeholder}>{placeholder}</span> : null}
    </span>
  );
}

export function HostApp({ host }: { host: MockHost }) {
  const id = useStore(host.store, (s) => s.scenarioId);
  if (id === 'chat') return <ChatApp host={host} />;
  if (id === 'form') return <FormApp host={host} />;
  return <ArticleApp host={host} />;
}

function ChatApp({ host }: { host: MockHost }) {
  const messages = useStore(host.store, (s) => s.messages);
  const typing = useStore(host.store, (s) => s.typingIndicator);
  const editor = useStore(host.store, (s) => s.editors.message!);
  const focused = useStore(host.store, (s) => s.focusedFieldId === 'message');
  const scroller = useRef<HTMLDivElement>(null);
  useEffect(() => {
    scroller.current?.scrollTo({ top: scroller.current.scrollHeight, behavior: 'smooth' });
  }, [messages.length, typing]);
  return (
    <div className={css.app}>
      <header className={css.appBar}>
        <ChevronLeft size={22} />
        <span className={css.avatar}>JD</span>
        <div className={css.appTitle}>
          <b>Jane Doe</b>
          <small>{typing ? 'typing…' : 'online'}</small>
        </div>
        <span className={css.appTag}>WhatsApp</span>
      </header>
      <div className={css.chat} ref={scroller}>
        {messages.map((m) => (
          <div key={m.id} className={m.from === 'me' ? css.bubbleMe : css.bubbleThem}>
            {m.text}
            <time>{new Date(m.at).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}</time>
          </div>
        ))}
        {typing ? <div className={`${css.bubbleThem} ${css.typing}`}>•••</div> : null}
      </div>
      <div className={css.composer}>
        <button type="button" className={css.composerField} data-focused={focused} onClick={() => host.focus('message')}>
          <FieldText editor={editor} placeholder="Message" focused={focused} />
        </button>
        <button type="button" className={css.sendBtn} onClick={() => host.action('send')} aria-label="Send">
          <SendIcon size={18} />
        </button>
      </div>
    </div>
  );
}

function FormApp({ host }: { host: MockHost }) {
  const sc = host.scenario;
  const editors = useStore(host.store, (s) => s.editors);
  const focusedId = useStore(host.store, (s) => s.focusedFieldId);
  const submitted = useStore(host.store, (s) => s.lastSubmitted);
  useEffect(() => {
    document.querySelector('[data-focused="true"]')?.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
  }, [focusedId]);
  return (
    <div className={css.app}>
      <header className={css.appBar}>
        <ChevronLeft size={22} />
        <div className={css.appTitle}>
          <b>Blossom Market</b>
          <small>blossom.market/seller/signup</small>
        </div>
        <span className={css.appTag}>Chrome</span>
      </header>
      <div className={css.form}>
        <h2>Create your seller account</h2>
        {sc.fields.map((f) => (
          <label key={f.id} className={css.formRow}>
            <span>
              {f.label}
              {f.field.sensitive ? <LockIcon size={12} /> : null}
            </span>
            <button
              type="button"
              className={f.field.kind === 'multiline' ? `${css.input} ${css.textarea}` : css.input}
              data-focused={focusedId === f.id}
              onClick={() => host.focus(f.id)}
            >
              <FieldText editor={editors[f.id]!} placeholder={f.field.hint ?? ''} focused={focusedId === f.id} secret={f.field.kind === 'password'} />
            </button>
          </label>
        ))}
        <button type="button" className={css.submit} onClick={() => host.action('done')}>
          Create account
        </button>
        {submitted ? <p className={css.note}>Submitted by you at {submitted}</p> : null}
      </div>
    </div>
  );
}

function ArticleApp({ host }: { host: MockHost }) {
  const article = host.scenario.article!;
  const selection = useStore(host.store, (s) => s.screenSelection);
  const editor = useStore(host.store, (s) => s.editors.comment!);
  const focused = useStore(host.store, (s) => s.focusedFieldId === 'comment');
  const selectable = new Set(article.selectable);
  return (
    <div className={css.app}>
      <header className={css.appBar}>
        <ChevronLeft size={22} />
        <div className={css.appTitle}>
          <b>{article.title}</b>
          <small>Tap an underlined word, then use the lens</small>
        </div>
        <span className={css.appTag}>Keep</span>
      </header>
      <article className={css.article}>
        {article.paragraphs.map((p, i) => (
          <p key={i}>
            {p.split(/(\s+)/).map((tok, j) => {
              const word = tok.replace(/[^\p{L}]/gu, '').toLowerCase();
              if (!selectable.has(word)) return tok;
              return (
                <button
                  type="button"
                  key={j}
                  className={css.word}
                  data-selected={selection === word}
                  onClick={() => host.selectOnScreen(selection === word ? null : word)}
                >
                  {tok}
                </button>
              );
            })}
          </p>
        ))}
      </article>
      <div className={css.composer}>
        <button type="button" className={css.composerField} data-focused={focused} onClick={() => host.focus('comment')}>
          <FieldText editor={editor} placeholder="Add a note" focused={focused} />
        </button>
      </div>
    </div>
  );
}
