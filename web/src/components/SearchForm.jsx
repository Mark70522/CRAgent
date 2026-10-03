import { Form, Input, Select, DatePicker, Button, Space } from 'antd'

/**
 * 通用搜索表单：读 columns 里带 search 的字段，自动生成对应控件。
 * @param {Array}    columns   字段配置（含 search 元信息）
 * @param {Function} onSearch  点「查询」时回调，参数是整理好的查询对象
 * @param {Function} onReset   点「重置」时回调
 */
export default function SearchForm({ columns, onSearch, onReset }) {
  const [form] = Form.useForm()
  const fields = columns.filter((c) => c.search) // 只挑出可搜字段

  // 没有任何可搜字段就不渲染搜索区
  if (fields.length === 0) return null

  // 点查询：把表单值整理成「后端能直接用」的查询对象
  const handleSearch = () => {
    const raw = form.getFieldsValue()
    const params = {}
    for (const c of fields) {
      const v = raw[c.key]
      if (v == null || v === '') continue // 跳过没填的（不发空参数）
      if (c.search.type === 'dateRange') {
        // 日期区间 → 拆成 startTime / endTime 两个字符串
        if (Array.isArray(v) && v.length === 2) {
          params.startTime = v[0].format('YYYY-MM-DD')
          params.endTime = v[1].format('YYYY-MM-DD')
        }
      } else {
        params[c.key] = v
      }
    }
    onSearch(params)
  }

  const handleReset = () => {
    form.resetFields()
    onReset()
  }

  return (
    <Form form={form} layout="inline" style={{ marginBottom: 16, rowGap: 12 }}>
      {fields.map((c) => (
        <Form.Item key={c.key} name={c.key} label={c.label}>
          {renderControl(c.search)}
        </Form.Item>
      ))}
      <Space>
        <Button type="primary" onClick={handleSearch}>查询</Button>
        <Button onClick={handleReset}>重置</Button>
      </Space>
    </Form>
  )
}

/** 按 search.type 渲染不同控件 —— 要支持新控件，加一个 case 即可 */
function renderControl(search) {
  switch (search.type) {
    case 'select':
      return <Select options={search.options} allowClear placeholder="请选择" style={{ width: 160 }} />
    case 'dateRange':
      return <DatePicker.RangePicker />
    default:
      return <Input allowClear placeholder="请输入" style={{ width: 160 }} />
  }
}
