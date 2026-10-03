import { useState } from 'react'
import { Segmented, Space } from 'antd'
import JsonForm from './JsonForm'
import JsonEditor from './JsonEditor'

/**
 * The same record two ways: a form driven by the field catalog, or the raw JSON that will be sent.
 * Switching keeps the values; the JSON view is the source of truth for what goes to the interface.
 */
export default function FieldsEditor({ catalog, value, original, onChange, readonly, onLearn, hide, extra }) {
  const [mode, setMode] = useState('form')
  return (
    <div>
      <Space style={{ marginBottom: 10, width: '100%', justifyContent: 'space-between' }} wrap>
        <Segmented size="small" value={mode} onChange={setMode} options={[{ value: 'form', label: '表单' }, { value: 'json', label: 'JSON' }]} />
        {extra}
      </Space>
      {mode === 'form'
        ? <JsonForm catalog={catalog} value={value} original={original} onChange={onChange} readonly={readonly} onLearn={onLearn} hide={hide} />
        : <JsonEditor value={value} onChange={onChange} readonly={readonly} />}
    </div>
  )
}
