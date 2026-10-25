/**
 * Mock 数据层
 * 作用：后端尚未就绪时，让前端可独立运行、完整演示所有功能。
 * 开启方式：项目根目录 .env.development 中设置 VITE_USE_MOCK=true
 * 数据来源：与 docs/04_数据库设计.sql 的测试数据保持一致。
 */

// —— 业务规则常量（与 docs/05 总览一致）——
const RULE = { maxBorrow: 5, borrowDays: 30, renewDays: 30, finePerDay: 0.5 }

// —— 深拷贝工具：mock 数据可变，需与初始快照隔离 ——
const clone = (o) => JSON.parse(JSON.stringify(o))

// —— 初始数据（对齐 SQL 测试数据）——
const INIT = {
  users: [
    {
      id: 1,
      username: 'admin',
      password: '123456',
      realName: '系统管理员',
      role: 'ADMIN',
      roleLabel: '系统管理员',
      readerNo: null,
      gender: 0,
      genderLabel: '未知',
      phone: '13800000001',
      status: 1,
      statusLabel: '正常',
      borrowedCount: 0,
      createTime: '2026-01-01 09:00:00'
    },
    {
      id: 2,
      username: 'librarian01',
      password: '123456',
      realName: '王采编',
      role: 'LIBRARIAN',
      roleLabel: '图书管理员',
      readerNo: null,
      gender: 1,
      genderLabel: '男',
      phone: '13800000002',
      status: 1,
      statusLabel: '正常',
      borrowedCount: 0,
      createTime: '2026-01-05 10:00:00'
    },
    {
      id: 3,
      username: 'reader001',
      password: '123456',
      realName: '张三',
      role: 'READER',
      roleLabel: '读者',
      readerNo: 'R20260001',
      gender: 1,
      genderLabel: '男',
      phone: '13800000003',
      status: 1,
      statusLabel: '正常',
      borrowedCount: 2,
      createTime: '2026-03-12 14:20:00'
    },
    {
      id: 4,
      username: 'reader002',
      password: '123456',
      realName: '李四',
      role: 'READER',
      roleLabel: '读者',
      readerNo: 'R20260002',
      gender: 2,
      genderLabel: '女',
      phone: '13800000004',
      status: 0,
      statusLabel: '冻结',
      borrowedCount: 1,
      createTime: '2026-03-15 09:30:00'
    },
    {
      id: 5,
      username: 'reader003',
      password: '123456',
      realName: '王五',
      role: 'READER',
      roleLabel: '读者',
      readerNo: 'R20260003',
      gender: 1,
      genderLabel: '男',
      phone: '13800000005',
      status: 1,
      statusLabel: '正常',
      borrowedCount: 0,
      createTime: '2026-04-02 16:45:00'
    },
    {
      id: 6,
      username: 'reader004',
      password: '123456',
      realName: '赵六',
      role: 'READER',
      roleLabel: '读者',
      readerNo: 'R20260004',
      gender: 2,
      genderLabel: '女',
      phone: '13800000006',
      status: 1,
      statusLabel: '正常',
      borrowedCount: 1,
      createTime: '2026-04-20 11:10:00'
    }
  ],
  categories: [
    { id: 1, name: '计算机技术', sort: 1 },
    { id: 2, name: '文学', sort: 2 },
    { id: 3, name: '历史', sort: 3 },
    { id: 4, name: '经济管理', sort: 4 }
  ],
  publishers: [
    { id: 1, name: '人民邮电出版社', contact: '010-81055000', address: '北京市丰台区成寿寺路11号' },
    {
      id: 2,
      name: '机械工业出版社',
      contact: '010-88379000',
      address: '北京市西城区百万庄大街22号'
    },
    { id: 3, name: '中华书局', contact: '010-63401767', address: '北京市丰台区太平桥西里38号' },
    {
      id: 4,
      name: '人民文学出版社',
      contact: '010-65241325',
      address: '北京市朝阳区农展馆南里10号'
    }
  ],
  books: [
    {
      id: 1,
      isbn: '9787111544937',
      title: '深入理解计算机系统（第3版）',
      author: 'Randal E. Bryant',
      categoryId: 1,
      categoryName: '计算机技术',
      publisherId: 1,
      publisherName: '人民邮电出版社',
      publishDate: '2016-11-01',
      price: 139.0,
      totalStock: 8,
      stock: 7,
      borrowCount: 120,
      location: 'A区-3排-01',
      description: '计算机系统基础经典教材，从程序员视角理解计算机底层原理。'
    },
    {
      id: 2,
      isbn: '9787111407010',
      title: '算法导论（原书第3版）',
      author: 'Thomas H. Cormen',
      categoryId: 1,
      categoryName: '计算机技术',
      publisherId: 2,
      publisherName: '机械工业出版社',
      publishDate: '2013-01-01',
      price: 128.0,
      totalStock: 3,
      stock: 3,
      borrowCount: 80,
      location: 'A区-3排-02',
      description: '算法领域标准教材，全面阐述算法设计与分析。'
    },
    {
      id: 3,
      isbn: '9787111641247',
      title: '深入理解Java虚拟机（第3版）',
      author: '周志明',
      categoryId: 1,
      categoryName: '计算机技术',
      publisherId: 2,
      publisherName: '机械工业出版社',
      publishDate: '2019-12-01',
      price: 129.0,
      totalStock: 1,
      stock: 0,
      borrowCount: 200,
      location: 'A区-3排-03',
      description: 'Java 虚拟机领域权威著作。'
    },
    {
      id: 4,
      isbn: '9787020002207',
      title: '红楼梦',
      author: '曹雪芹',
      categoryId: 2,
      categoryName: '文学',
      publisherId: 4,
      publisherName: '人民文学出版社',
      publishDate: '1996-12-01',
      price: 59.7,
      totalStock: 6,
      stock: 5,
      borrowCount: 60,
      location: 'B区-1排-01',
      description: '中国古典四大名著之一。'
    },
    {
      id: 5,
      isbn: '9787101052039',
      title: '万历十五年',
      author: '黄仁宇',
      categoryId: 3,
      categoryName: '历史',
      publisherId: 3,
      publisherName: '中华书局',
      publishDate: '2007-01-01',
      price: 26.0,
      totalStock: 3,
      stock: 1,
      borrowCount: 45,
      location: 'B区-2排-03',
      description: '明史研究经典之作，"大历史观"的代表作。'
    },
    {
      id: 6,
      isbn: '9787020001392',
      title: '平凡的世界（全三册）',
      author: '路遥',
      categoryId: 2,
      categoryName: '文学',
      publisherId: 4,
      publisherName: '人民文学出版社',
      publishDate: '2004-05-01',
      price: 64.0,
      totalStock: 6,
      stock: 6,
      borrowCount: 90,
      location: 'B区-1排-02',
      description: '茅盾文学奖获奖作品，全景式表现中国当代城乡生活。'
    }
  ],
  borrowRecords: [
    {
      id: 1,
      userId: 3,
      userName: '张三',
      readerNo: 'R20260001',
      bookId: 1,
      bookTitle: '深入理解计算机系统（第3版）',
      borrowDate: '2026-09-01',
      dueDate: '2026-10-01',
      returnDate: null,
      status: 1,
      statusLabel: '在借',
      renewCount: 0
    },
    {
      id: 2,
      userId: 3,
      userName: '张三',
      readerNo: 'R20260001',
      bookId: 4,
      bookTitle: '红楼梦',
      borrowDate: '2026-08-15',
      dueDate: '2026-09-14',
      returnDate: null,
      status: 1,
      statusLabel: '在借',
      renewCount: 0
    },
    {
      id: 3,
      userId: 3,
      userName: '张三',
      readerNo: 'R20260001',
      bookId: 6,
      bookTitle: '平凡的世界（全三册）',
      borrowDate: '2026-08-01',
      dueDate: '2026-08-31',
      returnDate: '2026-08-28',
      status: 2,
      statusLabel: '已还',
      renewCount: 1
    },
    {
      id: 4,
      userId: 5,
      userName: '王五',
      readerNo: 'R20260003',
      bookId: 5,
      bookTitle: '万历十五年',
      borrowDate: '2026-08-01',
      dueDate: '2026-08-31',
      returnDate: '2026-09-05',
      status: 2,
      statusLabel: '已还',
      renewCount: 0
    },
    {
      id: 5,
      userId: 6,
      userName: '赵六',
      readerNo: 'R20260004',
      bookId: 5,
      bookTitle: '万历十五年',
      borrowDate: '2026-07-20',
      dueDate: '2026-08-19',
      returnDate: null,
      status: 1,
      statusLabel: '在借',
      renewCount: 0
    },
    {
      id: 6,
      userId: 4,
      userName: '李四',
      readerNo: 'R20260002',
      bookId: 5,
      bookTitle: '万历十五年',
      borrowDate: '2026-05-10',
      dueDate: '2026-06-09',
      returnDate: '2026-06-05',
      status: 3,
      statusLabel: '已赔偿',
      renewCount: 0
    },
    {
      id: 7,
      userId: 5,
      userName: '王五',
      readerNo: 'R20260003',
      bookId: 4,
      bookTitle: '红楼梦',
      borrowDate: '2026-07-01',
      dueDate: '2026-07-31',
      returnDate: '2026-08-01',
      status: 2,
      statusLabel: '已还',
      renewCount: 0
    },
    {
      id: 8,
      userId: 4,
      userName: '李四',
      readerNo: 'R20260002',
      bookId: 3,
      bookTitle: '深入理解Java虚拟机（第3版）',
      borrowDate: '2026-08-10',
      dueDate: '2026-09-09',
      returnDate: null,
      status: 1,
      statusLabel: '在借',
      renewCount: 0
    }
  ],
  fines: [
    {
      id: 1,
      userId: 5,
      userName: '王五',
      readerNo: 'R20260003',
      borrowId: 4,
      bookTitle: '万历十五年',
      amount: 2.5,
      type: 1,
      typeLabel: '逾期罚金',
      status: 1,
      statusLabel: '未缴',
      paidTime: null,
      remark: '逾期5天 × 0.5元/天',
      createTime: '2026-09-05 10:00:00'
    },
    {
      id: 2,
      userId: 5,
      userName: '王五',
      readerNo: 'R20260003',
      borrowId: 7,
      bookTitle: '红楼梦',
      amount: 0.5,
      type: 1,
      typeLabel: '逾期罚金',
      status: 2,
      statusLabel: '已缴',
      paidTime: '2026-08-02 10:30:00',
      remark: '逾期1天 × 0.5元/天，当场缴清',
      createTime: '2026-08-01 17:00:00'
    },
    {
      id: 3,
      userId: 4,
      userName: '李四',
      readerNo: 'R20260002',
      borrowId: 6,
      bookTitle: '万历十五年',
      amount: 26.0,
      type: 3,
      typeLabel: '遗失赔偿',
      status: 2,
      statusLabel: '已缴',
      paidTime: '2026-06-06 15:20:00',
      remark: '图书遗失，按定价赔偿（记录状态改为已赔偿）',
      createTime: '2026-06-05 09:00:00'
    }
  ],
  notices: [
    {
      id: 1,
      title: '2026年秋季学期开馆时间公告',
      content: '本学期图书馆开放时间为每日 8:00—22:00，法定节假日另行通知。',
      publisherId: 1,
      publisherName: '系统管理员',
      status: 1,
      statusLabel: '已发布',
      createTime: '2026-09-01 08:00:00'
    },
    {
      id: 2,
      title: '计算机类新书到馆通知',
      content: '新到《深入理解计算机系统（第3版）》等计算机类图书 8 种，欢迎借阅。',
      publisherId: 2,
      publisherName: '王采编',
      status: 1,
      statusLabel: '已发布',
      createTime: '2026-09-03 10:00:00'
    },
    {
      id: 3,
      title: '系统升级维护通知（已撤回）',
      content: '系统将于本周末进行升级维护。（本公告已过期撤回）',
      publisherId: 1,
      publisherName: '系统管理员',
      status: 0,
      statusLabel: '已撤回',
      createTime: '2026-08-20 14:00:00'
    }
  ],
  operationLogs: [
    {
      id: 1,
      userId: 2,
      userName: '王采编',
      operation: 'BORROW',
      detail:
        '为读者[R20260001 张三]办理借书：《深入理解计算机系统（第3版）》，应还日期 2026-10-01',
      ip: '192.168.1.10',
      createTime: '2026-09-01 10:00:00'
    },
    {
      id: 2,
      userId: 2,
      userName: '王采编',
      operation: 'RETURN',
      detail: '办理还书：读者[R20260003 王五]归还《万历十五年》，逾期5天，登记罚金2.50元',
      ip: '192.168.1.10',
      createTime: '2026-09-05 10:00:00'
    },
    {
      id: 3,
      userId: 1,
      userName: '系统管理员',
      operation: 'LOGIN',
      detail: '系统管理员登录成功',
      ip: '192.168.1.2',
      createTime: '2026-09-07 09:00:00'
    }
  ]
}

// —— 运行时可变的 mock 数据库（每次页面加载重新初始化）——
let db = clone(INIT)
let seq = { borrow: 100, fine: 100, log: 100, user: 100, book: 100, notice: 100 }

const today = () => new Date().toISOString().slice(0, 10)
const addDays = (d, n) => {
  const t = new Date(d)
  t.setDate(t.getDate() + n)
  return t.toISOString().slice(0, 10)
}

// —— 分页工具 ——
const pageList = (arr, query) => {
  const page = Number(query.page || 1)
  const size = Number(query.size || 10)
  const list = Array.isArray(arr) ? arr.slice() : []
  const total = list.length
  return { records: list.slice((page - 1) * size, page * size), total, page, size }
}

// —— mock 接口路由：根据 url + method 返回数据，未匹配返回 null ——
function route(url, method, config) {
  const path = url.split('?')[0]
  const query = {}
  ;(url.split('?')[1] || '').split('&').forEach((kv) => {
    const [k, v] = kv.split('=')
    if (k) query[k] = decodeURIComponent(v || '')
  })
  const body = config.data ? JSON.parse(config.data) : {}
  const idMatch = path.match(/\/(\d+)$/)
  const id = idMatch ? Number(idMatch[1]) : null

  // 登录
  if (method === 'post' && path === '/auth/login') {
    const u = db.users.find((x) => x.username === body.username && x.password === body.password)
    if (!u) return { code: 500, msg: '登录名或密码错误', data: null }
    if (u.status === 0) return { code: 500, msg: '账号已冻结，请联系管理员', data: null }
    const token = 'mock-token-' + u.id + '-' + Date.now()
    return { code: 200, msg: 'ok', data: { token, user: { ...u, password: undefined } } }
  }

  // 用户管理
  if (path === '/users' && method === 'get') {
    let list = db.users.slice()
    if (query.role) list = list.filter((x) => x.role === query.role)
    if (query.keyword)
      list = list.filter(
        (x) =>
          x.realName.includes(query.keyword) ||
          (x.readerNo || '').includes(query.keyword) ||
          x.username.includes(query.keyword)
      )
    if (query.status !== undefined && query.status !== '')
      list = list.filter((x) => x.status === Number(query.status))
    return {
      code: 200,
      msg: 'ok',
      data: pageList(
        list.map((x) => ({ ...x, password: undefined })),
        query
      )
    }
  }
  if (path === '/users' && method === 'post') {
    const u = {
      ...body,
      id: ++seq.user,
      createTime: today() + ' 00:00:00',
      status: 1,
      statusLabel: '正常',
      borrowedCount: 0,
      genderLabel: body.gender === 1 ? '男' : '女'
    }
    db.users.push(u)
    return { code: 200, msg: '新增成功', data: u }
  }
  // 特定操作分支必须放在通用 put 之前（/users/{id}/status、/users/{id}/password 会被通用分支误吞）
  if (path.startsWith('/users/') && path.endsWith('/status') && method === 'put') {
    const sid = Number((path.match(/\/(\d+)\/status$/) || [])[1])
    const u = db.users.find((x) => x.id === sid)
    if (!u) return { code: 500, msg: '用户不存在', data: null }
    u.status = u.status === 1 ? 0 : 1
    u.statusLabel = u.status === 1 ? '正常' : '冻结'
    return { code: 200, msg: 'ok', data: u }
  }
  if (path.startsWith('/users/') && path.endsWith('/password') && method === 'put') {
    return { code: 200, msg: '密码已重置为 123456', data: null }
  }
  if (path.startsWith('/users/') && method === 'put') {
    const u = db.users.find((x) => x.id === id)
    if (u) Object.assign(u, body)
    return { code: 200, msg: '保存成功', data: u }
  }
  if (path.startsWith('/users/') && method === 'delete') {
    db.users = db.users.filter((x) => x.id !== id)
    return { code: 200, msg: '删除成功', data: null }
  }

  // 图书
  if (path === '/books' && method === 'get') {
    let list = db.books.slice()
    if (query.keyword)
      list = list.filter(
        (x) =>
          x.title.includes(query.keyword) ||
          x.author.includes(query.keyword) ||
          x.isbn.includes(query.keyword)
      )
    if (query.categoryId) list = list.filter((x) => x.categoryId === Number(query.categoryId))
    if (query.onlyStock) list = list.filter((x) => x.stock > 0)
    return { code: 200, msg: 'ok', data: pageList(list, query) }
  }
  if (path.startsWith('/books/') && method === 'get') {
    return { code: 200, msg: 'ok', data: db.books.find((x) => x.id === id) || null }
  }
  if (path === '/books' && method === 'post') {
    const c = db.categories.find((x) => x.id === body.categoryId)
    const p = db.publishers.find((x) => x.id === body.publisherId)
    const b = {
      ...body,
      id: ++seq.book,
      categoryName: c ? c.name : '',
      publisherName: p ? p.name : '',
      // 与后端一致：可借数取 stock（缺省时回退 totalStock），而非固定用 totalStock
      stock: Number(body.stock ?? body.totalStock ?? 0),
      borrowCount: 0
    }
    db.books.push(b)
    return { code: 200, msg: '新增成功', data: b }
  }
  if (path.startsWith('/books/') && method === 'put') {
    const b = db.books.find((x) => x.id === id)
    if (b) Object.assign(b, body)
    return { code: 200, msg: '保存成功', data: b }
  }
  if (path.startsWith('/books/') && method === 'delete') {
    db.books = db.books.filter((x) => x.id !== id)
    return { code: 200, msg: '删除成功', data: null }
  }

  // 分类 / 出版社
  if (path === '/categories' && method === 'get')
    return { code: 200, msg: 'ok', data: db.categories }
  if (path === '/publishers' && method === 'get')
    return { code: 200, msg: 'ok', data: db.publishers }
  if (path === '/categories' && method === 'post') {
    const c = { ...body, id: db.categories.length + 1 }
    db.categories.push(c)
    return { code: 200, msg: '新增成功', data: c }
  }
  if (path === '/publishers' && method === 'post') {
    const p = { ...body, id: db.publishers.length + 1 }
    db.publishers.push(p)
    return { code: 200, msg: '新增成功', data: p }
  }
  if (path.startsWith('/categories/') && method === 'put') {
    const c = db.categories.find((x) => x.id === id)
    if (c) Object.assign(c, body)
    return { code: 200, msg: '保存成功', data: c }
  }
  if (path.startsWith('/publishers/') && method === 'put') {
    const p = db.publishers.find((x) => x.id === id)
    if (p) Object.assign(p, body)
    return { code: 200, msg: '保存成功', data: p }
  }
  if (path.startsWith('/categories/') && method === 'delete') {
    db.categories = db.categories.filter((x) => x.id !== id)
    return { code: 200, msg: '删除成功', data: null }
  }
  if (path.startsWith('/publishers/') && method === 'delete') {
    db.publishers = db.publishers.filter((x) => x.id !== id)
    return { code: 200, msg: '删除成功', data: null }
  }

  // 借阅记录查询
  if (path === '/borrow-records' && method === 'get') {
    let list = db.borrowRecords.slice()
    if (query.keyword)
      list = list.filter(
        (x) =>
          x.userName.includes(query.keyword) ||
          x.readerNo.includes(query.keyword) ||
          x.bookTitle.includes(query.keyword)
      )
    if (query.status !== undefined && query.status !== '')
      list = list.filter((x) => x.status === Number(query.status))
    if (query.userId) list = list.filter((x) => x.userId === Number(query.userId))
    // 借出日期范围筛选（docs/03：状态/日期筛选）
    if (query.startDate) list = list.filter((x) => x.borrowDate >= query.startDate)
    if (query.endDate) list = list.filter((x) => x.borrowDate <= query.endDate)
    return { code: 200, msg: 'ok', data: pageList(list, query) }
  }

  // 借书（核心：校验 + 事务）
  if (path === '/borrow' && method === 'post') {
    const reader = db.users.find((x) => x.id === body.userId)
    const book = db.books.find((x) => x.id === body.bookId)
    if (!reader || reader.role !== 'READER') return { code: 500, msg: '读者不存在', data: null }
    if (reader.status === 0) return { code: 500, msg: '读者账号已冻结，无法借书', data: null }
    if (reader.borrowedCount >= RULE.maxBorrow)
      return { code: 500, msg: `该读者借阅已达上限（${RULE.maxBorrow} 册）`, data: null }
    const overdue = db.borrowRecords.some(
      (r) => r.userId === reader.id && r.status === 1 && r.dueDate < today()
    )
    if (overdue) return { code: 500, msg: '该读者有逾期未还图书，请先归还', data: null }
    const unpaid = db.fines.some((f) => f.userId === reader.id && f.status === 1)
    if (unpaid) return { code: 500, msg: '该读者有未缴罚金，请先缴清', data: null }
    if (!book || book.stock <= 0) return { code: 500, msg: '库存不足或已发生变化', data: null }
    // 事务模拟：扣库存 + 记流水 + 更新读者在借数
    book.stock -= 1
    book.borrowCount += 1
    reader.borrowedCount += 1
    const rec = {
      id: ++seq.borrow,
      userId: reader.id,
      userName: reader.realName,
      readerNo: reader.readerNo,
      bookId: book.id,
      bookTitle: book.title,
      borrowDate: today(),
      dueDate: addDays(today(), RULE.borrowDays),
      returnDate: null,
      status: 1,
      statusLabel: '在借',
      renewCount: 0
    }
    db.borrowRecords.unshift(rec)
    return { code: 200, msg: '借书成功，应还日期 ' + rec.dueDate, data: rec }
  }

  // 还书（核心：逾期罚金）
  if (path === '/return' && method === 'post') {
    const rec = db.borrowRecords.find((x) => x.id === body.recordId)
    if (!rec || rec.status !== 1)
      return { code: 500, msg: '该记录已归还，请勿重复操作', data: null }
    const overdueDays = Math.floor((new Date(today()) - new Date(rec.dueDate)) / 86400000)
    rec.status = 2
    rec.statusLabel = '已还'
    rec.returnDate = today()
    const book = db.books.find((x) => x.id === rec.bookId)
    if (book) book.stock += 1
    const reader = db.users.find((x) => x.id === rec.userId)
    if (reader) reader.borrowedCount -= 1
    let fineMsg = ''
    if (overdueDays > 0) {
      const amount = overdueDays * RULE.finePerDay
      db.fines.unshift({
        id: ++seq.fine,
        userId: rec.userId,
        userName: rec.userName,
        readerNo: rec.readerNo,
        borrowId: rec.id,
        bookTitle: rec.bookTitle,
        amount,
        type: 1,
        typeLabel: '逾期罚金',
        status: 1,
        statusLabel: '未缴',
        paidTime: null,
        remark: `逾期${overdueDays}天 × ${RULE.finePerDay}元/天`,
        createTime: today() + ' 10:00:00'
      })
      fineMsg = `，逾期 ${overdueDays} 天，应收罚金 ${amount.toFixed(2)} 元`
    }
    return { code: 200, msg: '还书成功' + fineMsg, data: rec }
  }

  // 续借
  if (path === '/renew' && method === 'post') {
    const rec = db.borrowRecords.find((x) => x.id === body.recordId)
    if (!rec) return { code: 500, msg: '记录不存在', data: null }
    if (rec.status !== 1) return { code: 500, msg: '该书不在借状态', data: null }
    if (rec.dueDate < today())
      return { code: 500, msg: '该书已逾期，无法续借，请尽快归还', data: null }
    if (rec.renewCount >= 1) return { code: 500, msg: '每册图书仅可续借一次', data: null }
    rec.renewCount += 1
    rec.dueDate = addDays(rec.dueDate, RULE.renewDays)
    return { code: 200, msg: '续借成功，新应还日期 ' + rec.dueDate, data: rec }
  }

  // 罚金
  if (path === '/fines' && method === 'get') {
    let list = db.fines.slice()
    if (query.status !== undefined && query.status !== '')
      list = list.filter((x) => x.status === Number(query.status))
    if (query.userId) list = list.filter((x) => x.userId === Number(query.userId))
    return { code: 200, msg: 'ok', data: pageList(list, query) }
  }
  if (path.startsWith('/fines/') && path.endsWith('/pay') && method === 'put') {
    const f = db.fines.find((x) => x.id === id)
    f.status = 2
    f.statusLabel = '已缴'
    f.paidTime = today() + ' 12:00:00'
    return { code: 200, msg: '罚金已标记为已缴', data: f }
  }

  // 读者检索（借书办理：图书管理员按证号/姓名查询读者）
  if (path === '/readers' && method === 'get') {
    const kw = query.keyword || ''
    const list = db.users.filter(
      (x) =>
        x.role === 'READER' && ((x.realName || '').includes(kw) || (x.readerNo || '').includes(kw))
    )
    return { code: 200, msg: 'ok', data: list.map((x) => ({ ...x, password: undefined })) }
  }

  // 我的借阅（读者本人，从 mock token 解析当前用户：mock-token-{id}-{ts}）
  if (path === '/my-borrows' && method === 'get') {
    const token = (config.headers && config.headers['token']) || ''
    const uid = Number(token.split('-')[2])
    let list = db.borrowRecords.filter((r) => r.userId === uid)
    if (query.status !== undefined && query.status !== '')
      list = list.filter((x) => x.status === Number(query.status))
    return { code: 200, msg: 'ok', data: pageList(list, query) }
  }

  // 个人中心：查询当前用户
  if (path === '/profile' && method === 'get') {
    const token = (config.headers && config.headers['token']) || ''
    const uid = Number(token.split('-')[2])
    const u = db.users.find((x) => x.id === uid)
    if (!u) return { code: 401, msg: '未登录', data: null }
    return { code: 200, msg: 'ok', data: { ...u, password: undefined } }
  }
  // 个人中心：修改资料
  if (path === '/profile' && method === 'put') {
    const token = (config.headers && config.headers['token']) || ''
    const uid = Number(token.split('-')[2])
    const u = db.users.find((x) => x.id === uid)
    if (!u) return { code: 401, msg: '未登录', data: null }
    Object.assign(u, { realName: body.realName, gender: body.gender, phone: body.phone })
    return { code: 200, msg: '保存成功', data: { ...u, password: undefined } }
  }
  // 个人中心：修改密码（mock 演示不校验旧密码）
  if (path === '/profile/password' && method === 'put') {
    if (!body.newPassword) return { code: 500, msg: '新密码不能为空', data: null }
    return { code: 200, msg: '密码修改成功', data: null }
  }

  // 赔偿登记：记录状态改“已赔偿”，库存不恢复
  if (path === '/fines/compensate' && method === 'post') {
    const rec = db.borrowRecords.find((x) => x.id === body.recordId)
    if (!rec || rec.status !== 1)
      return { code: 500, msg: '该记录不在借状态，无法赔偿登记', data: null }
    rec.status = 3
    rec.statusLabel = '已赔偿'
    const reader = db.users.find((x) => x.id === rec.userId)
    if (reader && reader.borrowedCount > 0) reader.borrowedCount -= 1
    const f = {
      id: ++seq.fine,
      userId: rec.userId,
      userName: rec.userName,
      readerNo: rec.readerNo,
      borrowId: rec.id,
      bookTitle: rec.bookTitle,
      amount: body.amount,
      type: body.type || 2,
      typeLabel: body.type === 3 ? '遗失赔偿' : '破损赔偿',
      status: 1,
      statusLabel: '未缴',
      paidTime: null,
      remark: body.remark || '',
      createTime: today() + ' 10:00:00'
    }
    db.fines.unshift(f)
    return { code: 200, msg: '赔偿已登记', data: f }
  }

  // 统计
  if (path === '/stats/overview') {
    const data = {
      bookKinds: db.books.length,
      totalStock: db.books.reduce((a, b) => a + b.totalStock, 0),
      onBorrow: db.borrowRecords.filter((r) => r.status === 1).length,
      readerCount: db.users.filter((u) => u.role === 'READER').length,
      // 与后端口径一致：按借出/归还日期统计当日数据，不再硬编码
      todayBorrow: db.borrowRecords.filter((r) => r.borrowDate === today()).length,
      todayReturn: db.borrowRecords.filter((r) => r.status === 2 && r.returnDate === today()).length
    }
    return { code: 200, msg: 'ok', data }
  }
  if (path === '/stats/trend') {
    // 与后端一致：近 12 个月按借出日期聚合，标签带年份（如 2026年9月）
    const now = new Date()
    const months = []
    const values = []
    for (let i = 11; i >= 0; i--) {
      const d = new Date(now.getFullYear(), now.getMonth() - i, 1)
      const y = d.getFullYear()
      const m = d.getMonth() + 1
      const key = y + '-' + String(m).padStart(2, '0')
      months.push(y + '年' + m + '月')
      values.push(db.borrowRecords.filter((r) => (r.borrowDate || '').slice(0, 7) === key).length)
    }
    return { code: 200, msg: 'ok', data: { months, values } }
  }
  if (path === '/stats/pie') {
    const data = db.categories.map((c) => ({
      name: c.name,
      value: db.books.filter((b) => b.categoryId === c.id).reduce((a, b) => a + b.borrowCount, 0)
    }))
    return { code: 200, msg: 'ok', data }
  }
  if (path === '/stats/top') {
    const data = [...db.books].sort((a, b) => b.borrowCount - a.borrowCount).slice(0, 10)
    return { code: 200, msg: 'ok', data }
  }

  // 借阅规则（只读）：取值须与后端 FineConstant 保持一致
  if (path === '/rule/config' && method === 'get') {
    return {
      code: 200,
      msg: 'ok',
      data: { maxBorrow: 5, borrowDays: 30, renewDays: 30, finePerDay: 0.5 }
    }
  }

  // 公告
  if (path === '/notices' && method === 'get') {
    let list = db.notices.slice()
    if (query.onlyPublished) list = list.filter((x) => x.status === 1)
    return { code: 200, msg: 'ok', data: pageList(list, query) }
  }
  if (path === '/notices' && method === 'post') {
    const n = {
      ...body,
      id: ++seq.notice,
      status: 1,
      statusLabel: '已发布',
      createTime: today() + ' 00:00:00'
    }
    db.notices.unshift(n)
    return { code: 200, msg: '发布成功', data: n }
  }
  if (path.startsWith('/notices/') && method === 'put') {
    const n = db.notices.find((x) => x.id === id)
    if (n) Object.assign(n, body)
    return { code: 200, msg: '保存成功', data: n }
  }
  if (path.startsWith('/notices/') && method === 'delete') {
    db.notices = db.notices.filter((x) => x.id !== id)
    return { code: 200, msg: '删除成功', data: null }
  }

  // 操作日志
  if (path === '/logs' && method === 'get') {
    let list = db.operationLogs.slice()
    if (query.keyword)
      list = list.filter(
        (x) => x.detail.includes(query.keyword) || x.userName.includes(query.keyword)
      )
    return { code: 200, msg: 'ok', data: pageList(list, query) }
  }

  return null
}

/**
 * axios 自定义适配器：拦截所有请求交给本地 route 处理
 * 返回格式需符合 axios 的 AxiosResponse 约定
 */
export function mockAdapter(config) {
  return new Promise((resolve) => {
    const delay = 200 + Math.random() * 300 // 模拟网络延迟
    setTimeout(() => {
      // axios 1.x 自定义 adapter 下 params 不会并入 url，手动拼接查询串（mock 分页/关键词/状态筛选依赖它）
      let url = config.url || ''
      if (config.params && !url.includes('?')) {
        const qs = Object.entries(config.params)
          .filter(([, v]) => v !== undefined && v !== null && v !== '')
          .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(v)}`)
          .join('&')
        if (qs) url += '?' + qs
      }
      const result = route(url, (config.method || 'get').toLowerCase(), config)
      const body = result || { code: 404, msg: 'Mock 未匹配该接口: ' + config.url, data: null }
      resolve({ data: body, status: 200, statusText: 'OK', headers: {}, config })
    }, delay)
  })
}
